package zio.webhooks

import zio._
import zio.clock.Clock
import zio.prelude.NonEmptySet
import zio.stream.UStream
import zio.webhooks.WebhookDeliveryBatching._
import zio.webhooks.WebhookDeliverySemantics._
import zio.webhooks.WebhookError._
import zio.webhooks.WebhookServer._

import java.io.IOException
import java.time.Instant

/**
 * A [[WebhookServer]] subscribes to [[WebhookEvent]]s and reliably delivers them, i.e. failed
 * dispatches are retried once, followed by retries with exponential backoff. Retries are performed
 * until some duration after which webhooks will be marked [[WebhookStatus.Unavailable]] since some
 * [[java.time.Instant]]. Dispatches are batched iff a `batchConfig` is defined ''and'' a webhook's
 * delivery batching is [[WebhookDeliveryBatching.Batched]].
 *
 * A live server layer is provided in the companion object for convenience and proper resource
 * management.
 */
final case class WebhookServer( // TODO: split server into components? this is looking a little too much 😬
  webhookRepo: WebhookRepo,
  stateRepo: WebhookStateRepo,
  eventRepo: WebhookEventRepo,
  httpClient: WebhookHttpClient,
  errorHub: Hub[WebhookError],
  webhookState: RefM[Map[WebhookId, WebhookServer.WebhookState]],
  batchingQueue: Option[Queue[(Webhook, WebhookEvent)]],
  changeQueue: Queue[WebhookState.Change],
  config: WebhookServerConfig
) {

  /**
   * Attempts delivery of a [[WebhookDispatch]] to the webhook receiver. On successful delivery,
   * dispatched events are marked [[WebhookEventStatus.Delivered]]. On failure, retries are
   * enqueued for events from webhooks with at-least-once delivery semantics.
   */
  private def deliver(dispatch: WebhookDispatch): URIO[Clock, Unit] = {
    def startRetrying(id: WebhookId, map: Map[WebhookId, WebhookState]) =
      for {
        instant       <- clock.instant
        _             <- webhookRepo.setWebhookStatus(id, WebhookStatus.Retrying(instant))
        retryingState <- WebhookState.Retrying.make(config.retry.capacity)
        _             <- retryingState.queue.offer(dispatch)
        _             <- changeQueue.offer(WebhookState.Change.ToRetrying(id, retryingState.queue))
      } yield map.updated(id, retryingState)

    def updateWebhookState = {
      val id = dispatch.webhook.id
      webhookState.update { map =>
        map.get(id) match {
          case Some(WebhookState.Enabled)            =>
            startRetrying(id, map)
          case None                                  =>
            startRetrying(id, map)
          case Some(WebhookState.Retrying(_, queue)) =>
            queue.offer(dispatch) *> UIO(map)
          case Some(WebhookState.Disabled)           =>
            ??? // TODO: handle
          case Some(WebhookState.Unavailable)        =>
            ??? // TODO: handle
        }
      }
    }

    for {
      response <- httpClient.post(WebhookHttpRequest.fromDispatch(dispatch)).option
      _        <- {
        (dispatch.semantics, response) match {
          case (_, Some(WebhookHttpResponse(200))) =>
            if (dispatch.size == 1)
              eventRepo.setEventStatus(dispatch.head.key, WebhookEventStatus.Delivered)
            else
              eventRepo.setEventStatusMany(dispatch.keys, WebhookEventStatus.Delivered)
          case (AtLeastOnce, _)                    =>
            updateWebhookState
          case (AtMostOnce, _)                     =>
            eventRepo.setEventStatusMany(dispatch.events.map(_.key), WebhookEventStatus.Failed)
        }
      }.catchAll(errorHub.publish)
    } yield ()
  }

  private def dispatchNewEvent(webhook: Webhook, event: WebhookEvent): ZIO[Clock, WebhookError, Unit] =
    for {
      _ <- eventRepo.setEventStatus(event.key, WebhookEventStatus.Delivering)
      _ <- (webhook.batching, batchingQueue) match {
             case (Batched, Some(queue)) =>
               queue.offer((webhook, event.copy(status = WebhookEventStatus.Delivering)))
             case _                      =>
               deliver(WebhookDispatch(webhook, NonEmptyChunk(event)))
           }
    } yield ()

  /**
   * Exposes a way to listen for [[WebhookError]]s, namely missing webhooks or events. This provides
   * clients a way to handle server errors that would otherwise just fail silently.
   */
  def getErrors: UManaged[Dequeue[WebhookError]] =
    errorHub.subscribe

  /**
   * Starts the webhook server. The following are run concurrently:
   *
   *   - new webhook event subscription
   *   - event recovery for webhooks which need to deliver at least once
   *   - dispatch retry monitoring
   *   - dispatch batching, if configured and enabled per webhook
   *
   * The server is ready once it signals readiness to accept new events.
   */
  def start: URIO[Clock, Any] =
    for {
      latch <- Promise.make[Nothing, Unit]
      _     <- startNewEventSubscription(latch)
      _     <- startEventRecovery
      _     <- startRetryMonitoring
      _     <- startBatching
      _     <- latch.await
    } yield ()

  /**
   * Starts a fiber that listens to events queued for batched webhook dispatch.
   */
  private def startBatching: URIO[Clock, Fiber.Runtime[Nothing, Unit]] =
    (config.batching, batchingQueue) match {
      case (Some(WebhookServerConfig.Batching(_, maxSize, maxWaitTime)), Some(batchingQueue)) => {
          val getWebhookIdAndContentType = (webhook: Webhook, event: WebhookEvent) =>
            (webhook.id, event.headers.find(_._1.toLowerCase == "content-type"))

          UStream
            .fromQueue(batchingQueue)
            .groupByKey(getWebhookIdAndContentType.tupled, maxSize) {
              case (_, stream) =>
                stream
                  .groupedWithin(maxSize, maxWaitTime)
                  .map(NonEmptyChunk.fromChunk)
                  .collectSome
                  .mapM(events => deliver(WebhookDispatch(events.head._1, events.map(_._2))))
            }
            .runDrain
        }.forkAs("batching")
      case _                                                                                  =>
        ZIO.unit.fork
    }

  /**
   * Starts recovery of events with status [[WebhookEventStatus.Delivering]] for webhooks with
   * [[WebhookDeliverySemantics.AtLeastOnce]]. Recovery is done by reconstructing
   * [[WebhookServer.WebhookState]], the server's internal representation of webhooks it handles.
   * This is especially important.
   */
  private def startEventRecovery: UIO[Unit] = ZIO.unit

  /**
   * Starts new [[WebhookEvent]] subscription. Takes a latch which succeeds when the server is ready
   * to receive events.
   */
  private def startNewEventSubscription(latch: Promise[Nothing, Unit]) =
    eventRepo
      .getEventsByStatuses(NonEmptySet(WebhookEventStatus.New))
      .use { dequeue =>
        dequeue.poll *> latch.succeed(()) *> UStream.fromQueue(dequeue).foreach { newEvent =>
          val webhookId = newEvent.key.webhookId
          webhookRepo
            .getWebhookById(webhookId)
            .flatMap(ZIO.fromOption(_).orElseFail(MissingWebhookError(webhookId)))
            .flatMap(webhook => dispatchNewEvent(webhook, newEvent).when(webhook.isOnline))
            .catchAll(errorHub.publish(_).unit)
        }
      }
      .forkAs("new-event-subscription")

  /**
   * Starts retries a webhook's queue of dispatches. Retrying is done until the queue is exhausted.
   * If retrying times out, the webhook is set to [[WebhookStatus.Unavailable]] and all its events
   * are marked [[WebhookEventStatus.Failed]].
   */
  private def startRetrying(id: WebhookId, queue: Queue[WebhookDispatch]) =
    for {
      success   <- takeAndRetry(queue)
                     .repeat(
                       Schedule.recurUntil[Int](_ == 0) &&
                         Schedule.exponential(config.retry.exponentialBase, config.retry.exponentialFactor)
                     )
                     .timeoutTo(None)(Some(_))(config.retry.timeout)
      newStatus <- if (success.isDefined)
                     ZIO.succeed(WebhookStatus.Enabled)
                   else
                     clock.instant.map(WebhookStatus.Unavailable) <& eventRepo.setAllAsFailedByWebhookId(id)
      _         <- webhookRepo.setWebhookStatus(id, newStatus)
      _         <- webhookState.update(map => UIO(map.updated(id, WebhookState.Enabled)))
    } yield ()

  /**
   * Starts monitoring for internal webhook state changes, i.e. [[WebhookState.Change.ToRetrying]].
   */
  private def startRetryMonitoring = {
    for {
      update <- changeQueue.take
      _      <- (update match {
                    case WebhookState.Change.ToRetrying(id, queue) =>
                      startRetrying(id, queue).catchAll(errorHub.publish).fork
                  })
    } yield ()
  }.forever.forkAs("retry-monitoring")

  /**
   * Waits until all work in progress is finished, then shuts down.
   */
  def shutdown: IO[IOException, Any] = ZIO.unit

  /**
   * Takes a dispatch from a retry queue and attempts delivery. When successful, dispatch events are
   * marked [[WebhookEventStatus.Delivered]]. On failure, dispatch is put back into the queue.
   *
   * Returns the current queue size.
   */
  private def takeAndRetry(retryQueue: Queue[WebhookDispatch]) =
    for {
      dispatch    <- retryQueue.take
      response    <- httpClient.post(WebhookHttpRequest.fromDispatch(dispatch)).option
      _           <- response match {
                       case Some(WebhookHttpResponse(200)) =>
                         if (dispatch.size == 1)
                           eventRepo.setEventStatus(dispatch.head.key, WebhookEventStatus.Delivered)
                         else
                           eventRepo.setEventStatusMany(dispatch.keys, WebhookEventStatus.Delivered)
                       case _                              =>
                         retryQueue.offer(dispatch)
                     }
      currentSize <- retryQueue.size
    } yield currentSize
}

object WebhookServer {
  // TODO: Smart constructor

  type Env = Has[WebhookRepo]
    with Has[WebhookStateRepo]
    with Has[WebhookEventRepo]
    with Has[WebhookHttpClient]
    with Has[WebhookServerConfig]
    with Clock

  def getErrors: URManaged[Has[WebhookServer], Dequeue[WebhookError]] =
    ZManaged.service[WebhookServer].flatMap(_.getErrors)

  val live: URLayer[WebhookServer.Env, Has[WebhookServer]] = {
    for {
      serverConfig  <- ZManaged.service[WebhookServerConfig]
      state         <- RefM.makeManaged(Map.empty[WebhookId, WebhookServer.WebhookState])
      errorHub      <- Hub.sliding[WebhookError](serverConfig.errorSlidingCapacity).toManaged_
      batchingQueue <-
        ZIO
          .foreach(serverConfig.batching)(batching => Queue.bounded[(Webhook, WebhookEvent)](batching.capacity))
          .toManaged_
      changeQueue   <- Queue.bounded[WebhookState.Change](1).toManaged_
      webhookRepo   <- ZManaged.service[WebhookRepo]
      stateRepo     <- ZManaged.service[WebhookStateRepo]
      eventRepo     <- ZManaged.service[WebhookEventRepo]
      httpClient    <- ZManaged.service[WebhookHttpClient]
      server         = WebhookServer(
                         webhookRepo,
                         stateRepo,
                         eventRepo,
                         httpClient,
                         errorHub,
                         state,
                         batchingQueue,
                         changeQueue,
                         serverConfig
                       )
      _             <- server.start.toManaged_
      _             <- ZManaged.finalizer(server.shutdown.orDie)
    } yield server
  }.toLayer

  sealed trait WebhookState extends Product with Serializable
  object WebhookState {
    sealed trait Change
    object Change {
      final case class ToRetrying(id: WebhookId, queue: Queue[WebhookDispatch]) extends Change
    }

    case object Disabled extends WebhookState

    case object Enabled extends WebhookState

    final case class Retrying(sinceTime: Instant, queue: Queue[WebhookDispatch]) extends WebhookState

    object Retrying {
      def make(capacity: Int): URIO[Clock, Retrying] =
        ZIO.mapN(clock.instant, Queue.bounded[WebhookDispatch](capacity))(Retrying(_, _))
    }

    case object Unavailable extends WebhookState
  }
}