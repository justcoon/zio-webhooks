package zio.webhooks

import com.github.ghik.silencer.silent
import zio._
import zio.clock.Clock
import zio.duration._
import zio.json._
import zio.stream._
import zio.webhooks.WebhookDeliverySemantics._
import zio.webhooks.WebhookError._
import zio.webhooks.WebhookServer.WebhookState.Retrying
import zio.webhooks.WebhookServer._
import zio.webhooks.internal.CountDownLatch

import java.io.IOException
import java.time.{ Instant, Duration => JDuration }

/**
 * A [[WebhookServer]] is a stateful server that subscribes to [[WebhookEvent]]s and reliably
 * delivers them, i.e. failed dispatches are retried once, followed by retries with exponential
 * backoff. Retries are performed until some duration after which webhooks will be marked
 * [[WebhookStatus.Unavailable]] since some [[java.time.Instant]]. Dispatches are batched if and
 * only if a batching capacity is configured ''and'' a webhook's delivery batching is
 * [[WebhookDeliveryBatching.Batched]]. When [[shutdown]] is called, a [[shutdownSignal]] is sent
 * which lets all dispatching work finish. Finally, the retry state is persisted, which allows
 * retrying to resume after server restarts.
 *
 * A [[live]] server layer is provided in the companion object for convenience and proper resource
 * management, ensuring [[shutdown]] is called by the finalizer.
 */
final class WebhookServer private (
  private val config: WebhookServerConfig,
  private val eventRepo: WebhookEventRepo,
  private val httpClient: WebhookHttpClient,
  private val stateRepo: WebhookStateRepo,
  private val webhookRepo: WebhookRepo,
  private val errorHub: Hub[WebhookError],
  private val internalState: RefM[InternalState],
  private val newRetries: Queue[NewRetry],
  private val startupLatch: CountDownLatch,
  private val shutdownLatch: CountDownLatch,
  private val shutdownSignal: Promise[Nothing, Unit]
) {

  private def batchGroup(
    batchingCapacity: Int,
    batchQueues: RefM[Map[BatchKey, Queue[WebhookEvent]]],
    batchKey: BatchKey,
    batchEvents: UStream[WebhookEvent]
  ): ZIO[Clock, MissingWebhookError, Unit] =
    for {
      webhook <- webhookRepo.requireWebhook(batchKey.webhookId)
      _       <- webhook.deliveryMode.batching match {
                   case WebhookDeliveryBatching.Single  =>
                     batchEvents.mapMParUnordered(config.maxSingleDispatchConcurrency)(deliverNewEvent).runDrain
                   case WebhookDeliveryBatching.Batched =>
                     for {
                       batchQueue <- batchQueues.modify { map =>
                                       map.get(batchKey) match {
                                         case Some(queue) =>
                                           UIO((queue, map))
                                         case None        =>
                                           for (queue <- Queue.bounded[WebhookEvent](batchingCapacity))
                                             yield (queue, map + (batchKey -> queue))
                                       }
                                     }
                       latch      <- Promise.make[Nothing, Unit]
                       _          <- doBatching(webhook, batchQueue, latch).fork
                       _          <- latch.await
                       _          <- batchEvents.run(ZSink.fromQueue(batchQueue))
                     } yield ()
                 }
    } yield ()

  private def getRetryingState(webhookId: WebhookId, internalState: InternalState): URIO[Clock, Retrying] =
    internalState.webhookState.get(webhookId) match {
      case Some(retrying: WebhookState.Retrying) =>
        UIO(retrying)
      case _                                     =>
        WebhookState.Retrying.make(config.retry)
    }

  /**
   * Attempts delivery of a [[WebhookDispatch]] to a webhook's endpoint. On successful delivery,
   * events are marked [[WebhookEventStatus.Delivered]]. On failure, dispatches from webhooks with
   * at-least-once delivery semantics are enqueued for retrying.
   */
  private def deliver(dispatch: WebhookDispatch): URIO[Clock, Unit] = {
    for {
      _        <- markDispatch(dispatch, WebhookEventStatus.Delivering)
      response <- httpClient.post(WebhookHttpRequest.fromDispatch(dispatch)).option
      _        <- (dispatch.deliverySemantics, response) match {
                    case (_, Some(WebhookHttpResponse(200))) =>
                      markDispatch(dispatch, WebhookEventStatus.Delivered)
                    case (AtMostOnce, _)                     =>
                      markDispatch(dispatch, WebhookEventStatus.Failed)
                    case (AtLeastOnce, _)                    =>
                      val webhookId = dispatch.webhookId
                      internalState.update { internalState =>
                        internalState.webhookState.get(webhookId) match {
                          case Some(retryingState: Retrying) =>
                            for {
                              retryingState <- retryingState.setActiveWithTimeout(markWebhookUnavailable(webhookId))
                              _             <- retryingState.enqueueAll(dispatch.events)
                            } yield internalState.updateWebhookState(webhookId, retryingState)
                          case _                             => // change to retrying state
                            for {
                              retryingState <- getRetryingState(webhookId, internalState)
                              retryingState <- retryingState.setActiveWithTimeout(markWebhookUnavailable(webhookId))
                              _             <- retryingState.enqueueAll(dispatch.events)
                              _             <- newRetries.offer(NewRetry(webhookId, retryingState))
                            } yield internalState.updateWebhookState(webhookId, retryingState)
                        }
                      }
                  }
    } yield ()
  }.catchAll(errorHub.publish(_).unit)

  private def deliverNewEvent(newEvent: WebhookEvent): URIO[Clock, Unit] = {
    for {
      webhook <- webhookRepo.requireWebhook(newEvent.key.webhookId)
      dispatch = WebhookDispatch(webhook.id, webhook.url, webhook.deliveryMode.semantics, NonEmptyChunk(newEvent))
      _       <- deliver(dispatch).when(webhook.isAvailable)
    } yield ()
  }.catchAll(errorHub.publish(_).unit)

  private def doBatching(
    webhook: Webhook,
    batchQueue: Dequeue[WebhookEvent],
    latch: Promise[Nothing, Unit]
  ): URIO[Clock, Nothing] = {
    val deliverBatch = for {
      batch   <- batchQueue.take.zipWith(batchQueue.takeAll)(NonEmptyChunk.fromIterable(_, _))
      dispatch = WebhookDispatch(webhook.id, webhook.url, webhook.deliveryMode.semantics, batch)
      _       <- deliver(dispatch).when(webhook.isAvailable)
    } yield ()
    batchQueue.poll *> latch.succeed(()) *> deliverBatch.forever
  }

  private def doRetryBatching(
    webhook: Webhook,
    batchQueue: Queue[WebhookEvent],
    latch: Promise[Nothing, Unit],
    retryingState: Ref[WebhookState.Retrying]
  ): ZIO[Clock, WebhookError, Nothing] = {
    val deliverBatch =
      for {
        batchEvents <- batchQueue.take.zipWith(batchQueue.takeAll)(NonEmptyChunk.fromIterable(_, _))
        _           <- retryEvents(webhook.id, retryingState, batchEvents, Some(batchQueue))
      } yield ()
    batchQueue.poll *> latch.succeed(()) *> deliverBatch.forever
  }

  /**
   * Exposes a way to listen for [[WebhookError]]s. This provides clients a way to handle server
   * errors that would otherwise just fail silently.
   */
  def getErrors: UManaged[Dequeue[WebhookError]] =
    errorHub.subscribe

  private def markDispatch(dispatch: WebhookDispatch, newStatus: WebhookEventStatus): IO[WebhookError, Unit] =
    if (dispatch.size == 1)
      eventRepo.setEventStatus(dispatch.head.key, newStatus)
    else
      eventRepo.setEventStatusMany(dispatch.keys, newStatus)

  private def markWebhookUnavailable(webhookId: WebhookId): ZIO[Clock, WebhookError, Unit] =
    for {
      _           <- eventRepo.setAllAsFailedByWebhookId(webhookId)
      unavailable <- clock.instant.map(WebhookStatus.Unavailable)
      _           <- webhookRepo.setWebhookStatus(webhookId, unavailable)
      _           <- internalState.update(state => UIO(state.updateWebhookState(webhookId, WebhookState.Unavailable)))
    } yield ()

  /**
   * Merges a stream with the shutdown signal, terminating it when the signal arrives.
   */
  private def mergeShutdown[A](stream: UStream[A]): UStream[A] =
    stream
      .map(Left(_))
      .mergeTerminateRight(UStream.fromEffect(shutdownSignal.await.map(Right(_))))
      .collectLeft

  /**
   * Reconstructs the server's internal retrying states from the loaded server state.
   */
  private def reconstructInternalState(loadedState: PersistentServerState): ZIO[Clock, WebhookError, Unit] =
    for {
      retryingMap <- ZIO.foreach(loadedState.retryingStates) {
                       case (id, persistedState) =>
                         for {
                           loadedState <- ZIO.mapN(
                                            Queue.bounded[WebhookEvent](config.retry.capacity),
                                            Queue.bounded[Promise[Nothing, Unit]](config.retry.capacity)
                                          ) { (retryQueue, backoffResets) =>
                                            WebhookState.Retrying(
                                              retryQueue,
                                              backoffResets,
                                              config.retry.exponentialBase,
                                              config.retry.exponentialFactor,
                                              maxBackoff = config.retry.maxBackoff,
                                              persistedState.timeLeft,
                                              persistedState.sinceTime,
                                              persistedState.lastRetryTime,
                                              persistedState.attempt,
                                              persistedState.backoff,
                                              None
                                            )
                                          }
                           webhookId    = WebhookId(id)
                           activeState <- loadedState.setActiveWithTimeout(markWebhookUnavailable(webhookId))
                         } yield (webhookId, activeState)
                     }
      _           <- internalState.set(InternalState(retryingMap))
      _           <- ZIO.foreach_(retryingMap) {
                       case (webhookId, retryingState) =>
                         newRetries.offer(NewRetry(webhookId, retryingState))
                     }
    } yield ()

  def retryBatched(
    retryingState: Ref[WebhookState.Retrying],
    webhookId: WebhookId,
    batchingCapacity: Int
  ): URIO[Clock, Unit] =
    for {
      batchQueues <- RefM.make(Map.empty[BatchKey, Queue[WebhookEvent]])
      retryQueue  <- retryingState.get.map(_.retryQueue)
      _           <- mergeShutdown(UStream.fromQueue(retryQueue))
                       .groupByKey(ev => BatchKey(webhookId, ev.contentType)) {
                         case (batchKey, batchEvents) =>
                           ZStream.fromEffect {
                             (for {
                               webhook    <- webhookRepo.requireWebhook(batchKey.webhookId)
                               batchQueue <- batchQueues.modify { map =>
                                               map.get(batchKey) match {
                                                 case Some(queue) =>
                                                   UIO((queue, map))
                                                 case None        =>
                                                   for (queue <- Queue.bounded[WebhookEvent](batchingCapacity))
                                                     yield (queue, map + (batchKey -> queue))
                                               }
                                             }
                               latch      <- Promise.make[Nothing, Unit]
                               _          <- doRetryBatching(
                                               webhook,
                                               batchQueue,
                                               latch,
                                               retryingState
                                             ).fork
                               _          <- latch.await
                               _          <- batchEvents.run(ZSink.fromQueue(batchQueue))
                             } yield ()).catchAll(errorHub.publish(_).unit)
                           }
                       }
                       .runDrain
                       .fork
    } yield ()

  private def retryEvents(
    webhookId: WebhookId,
    retryingState: Ref[WebhookState.Retrying],
    events: NonEmptyChunk[WebhookEvent],
    batchQueue: Option[Queue[WebhookEvent]] = None
  ): ZIO[Clock, WebhookError, Unit] =
    for {
      webhook  <- webhookRepo.requireWebhook(webhookId)
      _        <- retryingState.update(_.addInFlight(events))
      dispatch  = WebhookDispatch(
                    webhook.id,
                    webhook.url,
                    webhook.deliveryMode.semantics,
                    events
                  )
      response <- httpClient.post(WebhookHttpRequest.fromDispatch(dispatch)).option
      _        <- response match {
                    case Some(WebhookHttpResponse(200)) =>
                      for {
                        _                <- retryingState.update(_.removeInFlight(events))
                        _                <- markDispatch(dispatch, WebhookEventStatus.Delivered)
                        now              <- clock.instant
                        newState         <- internalState.modify { internalState =>
                                              for {
                                                newState <- retryingState.updateAndGet(_.resetBackoff(now))
                                                _        <- retryingState.get.flatMap {
                                                              _.backoffResets.takeAll
                                                                .flatMap(ZIO.foreach_(_)(_.succeed(())))
                                                            }
                                              } yield (
                                                newState,
                                                internalState.updateWebhookState(webhookId, newState)
                                              )
                                            }
                        queueEmpty       <- newState.retryQueue.size.map(_ <= 0)
                        batchExistsEmpty <- ZIO.foreach(batchQueue)(_.size.map(_ <= 0))
                        inFlightEmpty    <- retryingState.get.map(_.inFlight.isEmpty)
                        allEmpty          = queueEmpty && inFlightEmpty && batchExistsEmpty.getOrElse(true)
                        setInactive       = retryingState.get.flatMap(_.setInactive)
                        _                <- setInactive.when(allEmpty)
                      } yield ()
                    case _                              => // retry failed
                      for {
                        timestamp <- clock.instant
                        nextState <- retryingState.updateAndGet(_.increaseBackoff(timestamp))
                        _         <- internalState.update(state => UIO(state.updateWebhookState(webhookId, nextState)))
                        requeue    = nextState.requeue(events) *> retryingState.update(_.removeInFlight(events))
                        // prevent batches from getting into deadlocks by forking the requeue
                        _         <- if (batchQueue.isDefined) requeue.fork else requeue
                      } yield ()
                  }
    } yield ()

  private def retrySingly(retryingState: Ref[Retrying], webhookId: WebhookId): ZIO[Clock, Nothing, Unit] =
    for {
      retryQueue <- retryingState.get.map(_.retryQueue)
      _          <- mergeShutdown(UStream.fromQueue(retryQueue))
                      .mapMParUnordered(config.maxSingleDispatchConcurrency) { event =>
                        retryEvents(
                          webhookId,
                          retryingState,
                          NonEmptyChunk.single(event)
                        ).catchAll(errorHub.publish)
                      }
                      .runDrain
                      .fork
    } yield ()

  /**
   * Starts the webhook server. The following are run concurrently:
   *
   *   - new webhook event subscription
   *   - event recovery for webhooks with at-least-once delivery semantics
   *   - dispatch retry monitoring
   *   - dispatch batching, if configured and enabled per webhook
   *
   * The server is ready once it signals readiness to accept new events.
   */
  def start: URIO[Clock, Any] =
    for {
      _ <- startEventRecovery
      _ <- startRetryMonitoring
      _ <- startNewEventSubscription
      _ <- startupLatch.await
    } yield ()

  private def startBatching(dequeue: Dequeue[WebhookEvent], batchingCapacity: Int): URIO[Clock, Unit] =
    for {
      batchQueues <- RefM.make(Map.empty[BatchKey, Queue[WebhookEvent]])
      _           <- mergeShutdown(UStream.fromQueue(dequeue)).groupByKey { ev =>
                       val (webhookId, contentType) = ev.webhookIdAndContentType
                       BatchKey(webhookId, contentType)
                     } {
                       case (batchKey, events) =>
                         ZStream.fromEffect {
                           batchGroup(batchingCapacity, batchQueues, batchKey, events)
                             .catchAll(errorHub.publish(_).unit)
                         }
                     }.runDrain
    } yield ()

  /**
   * Starts recovery of events with status [[WebhookEventStatus.Delivering]] for webhooks with
   * delivery semantics [[WebhookDeliverySemantics.AtLeastOnce]]. Recovery is done by reconstructing
   * [[WebhookServer.InternalState]], the server's internal representation of webhooks it handles.
   * Events are loaded incrementally and are queued for retrying.
   *
   * This ensures retries are persistent with respect to server restarts.
   */
  private def startEventRecovery: URIO[Clock, Any] = {
    def recover(event: WebhookEvent): URIO[Clock, Unit] =
      for {
        retryQueue <- internalState.modify { state =>
                        state.webhookState.get(event.key.webhookId) match {
                          // we're continuing retries for this webhook
                          case Some(retrying: WebhookState.Retrying) =>
                            UIO((Some(retrying.retryQueue), state))
                          // no retry state was loaded for this webhook, make a new one
                          case None                                  =>
                            ZIO.mapN(
                              Queue.bounded[WebhookEvent](config.retry.capacity),
                              Queue.bounded[Promise[Nothing, Unit]](config.retry.capacity),
                              clock.instant
                            ) { (retryQueue, backoffResetsQueue, now) =>
                              (
                                Some(retryQueue),
                                state.updateWebhookState(
                                  event.key.webhookId,
                                  WebhookState.Retrying(
                                    retryQueue,
                                    backoffResetsQueue,
                                    base = config.retry.exponentialBase,
                                    power = config.retry.exponentialFactor,
                                    maxBackoff = config.retry.maxBackoff,
                                    timeout = config.retry.timeout,
                                    activeSinceTime = now,
                                    lastRetryTime = now,
                                    nextBackoff = config.retry.exponentialBase
                                  )
                                )
                              )
                            }
                          case _                                     =>
                            UIO((None, state))
                        }
                      }
        _          <- ZIO.foreach_(retryQueue)(_.offer(event))
      } yield ()

    for {
      _ <- stateRepo.getState.flatMap {
             case Some(rawState) =>
               ZIO
                 .fromEither(rawState.fromJson[PersistentServerState])
                 .mapError(message => InvalidStateError(rawState, message))
                 .flatMap(reconstructInternalState)
             case None           =>
               reconstructInternalState(PersistentServerState.empty)
           }.catchAll(errorHub.publish)
      _ <- mergeShutdown(eventRepo.recoverEvents).foreach { event =>
             (for {
               webhook <- webhookRepo.requireWebhook(event.key.webhookId)
               _       <- recover(event).when(webhook.isAvailable)
             } yield ()).catchAll(errorHub.publish)
           }
    } yield ()
  }.fork *> startupLatch.countDown

  /**
   * Starts new [[WebhookEvent]] subscription. Counts down on the `startupLatch` signalling it's
   * ready to accept events.
   */
  private def startNewEventSubscription: URIO[Clock, Any] =
    eventRepo.subscribeToNewEvents.use { eventDequeue =>
      for {
        _           <- eventDequeue.poll *> startupLatch.countDown
        isShutdown  <- shutdownSignal.isDone
        handleEvents = config.batchingCapacity match {
                         case Some(capacity) =>
                           startBatching(eventDequeue, capacity)
                         case None           =>
                           mergeShutdown(UStream.fromQueue(eventDequeue)).foreach(deliverNewEvent)
                       }
        _           <- handleEvents.unless(isShutdown)
        _           <- shutdownLatch.countDown
      } yield ()
    }.fork

  /**
   * Starts a retry dispatching for a webhook.
   */
  private def startRetryMonitoring: URIO[Clock, Any] = {
    mergeShutdown(UStream.fromQueue(newRetries)).foreach {
      case NewRetry(webhookId, retrying) =>
        (for {
          retryingState <- Ref.make(retrying)
          webhook       <- webhookRepo.requireWebhook(webhookId)
          _             <- (webhook.batching, config.batchingCapacity) match {
                             case (WebhookDeliveryBatching.Batched, Some(capacity)) =>
                               retryBatched(retryingState, webhookId, capacity)
                             case _                                                 =>
                               retrySingly(retryingState, webhookId)
                           }
        } yield ()).catchAll(errorHub.publish)
    } *> shutdownLatch.countDown
  }.fork

  /**
   * Waits until all work in progress is finished, then shuts down.
   */
  def shutdown: ZIO[Clock, IOException, Any] =
    for {
      _               <- shutdownSignal.succeed(())
      _               <- shutdownLatch.await
      timestamp       <- clock.instant
      persistentState <- internalState.modify { internalState =>
                           val suspendedState = internalState.suspendRetries(timestamp)
                           UIO((toPersistentServerState(suspendedState), suspendedState))
                         }
      _               <- stateRepo.setState(persistentState.toJson)
    } yield ()

  /**
   * Maps the server's internal retrying states into a [[PersistentServerState]].
   */
  private def toPersistentServerState(internalState: InternalState): PersistentServerState =
    PersistentServerState(internalState.webhookState.collect {
      case (webhookId, retrying: WebhookState.Retrying) =>
        val retryingState = PersistentServerState.RetryingState(
          sinceTime = retrying.activeSinceTime,
          lastRetryTime = retrying.lastRetryTime,
          timeLeft = retrying.timeout,
          backoff = retrying.nextBackoff,
          attempt = retrying.failureCount
        )
        (webhookId.value, retryingState)
    })
}

object WebhookServer {
  final case class BatchKey(webhookId: WebhookId, contentType: Option[String])

  /**
   * Creates a server, pulling dependencies from the environment then initializing internal state.
   */
  def create: URIO[Env, WebhookServer] =
    for {
      config         <- ZIO.service[WebhookServerConfig]
      eventRepo      <- ZIO.service[WebhookEventRepo]
      httpClient     <- ZIO.service[WebhookHttpClient]
      webhookRepo    <- ZIO.service[WebhookRepo]
      webhookState   <- ZIO.service[WebhookStateRepo]
      errorHub       <- Hub.sliding[WebhookError](config.errorSlidingCapacity)
      newRetries     <- Queue.bounded[NewRetry](config.retry.capacity)
      state          <- RefM.make(InternalState(Map.empty))
      // startup sync points: new event sub + event recovery
      startupLatch   <- CountDownLatch.make(2)
      // shutdown sync points: new event sub + event recovery + retrying
      shutdownLatch  <- CountDownLatch.make(2)
      shutdownSignal <- Promise.make[Nothing, Unit]
    } yield new WebhookServer(
      config,
      eventRepo,
      httpClient,
      webhookState,
      webhookRepo,
      errorHub,
      state,
      newRetries,
      startupLatch,
      shutdownLatch,
      shutdownSignal
    )

  type Env = Has[WebhookRepo]
    with Has[WebhookStateRepo]
    with Has[WebhookEventRepo]
    with Has[WebhookHttpClient]
    with Has[WebhookServerConfig]
    with Clock

  def getErrors: URManaged[Has[WebhookServer], Dequeue[WebhookError]] =
    ZManaged.service[WebhookServer].flatMap(_.getErrors)

  /**
   * The server's [[InternalState]] is the state of its webhooks. The server uses its internal
   * representation of each webhook's state to perform retrying logic.
   */
  private[webhooks] final case class InternalState(webhookState: Map[WebhookId, WebhookState]) {
    def suspendRetries(timestamp: Instant): InternalState =
      copy(webhookState = webhookState.map {
        case (id, retrying: WebhookState.Retrying) =>
          (id, retrying.suspend(timestamp))
        case (id, state)                           => (id, state)
      })

    def updateWebhookState(id: WebhookId, newWebhookState: WebhookState): InternalState =
      copy(webhookState = webhookState.updated(id, newWebhookState))
  }

  /**
   * Creates a server, ensuring shutdown on release.
   */
  val live: URLayer[WebhookServer.Env, Has[WebhookServer]] = {
    for {
      server <- WebhookServer.create.toManaged_
      _      <- server.start.toManaged_
      _      <- ZManaged.finalizer(server.shutdown.orDie)
    } yield server
  }.toLayer

  final case class NewRetry(id: WebhookId, retryingState: WebhookState.Retrying)

  def shutdown: ZIO[Has[WebhookServer] with Clock, IOException, Any] =
    ZIO.environment[Has[WebhookServer] with Clock].flatMap(_.get[WebhookServer].shutdown)

  /**
   * [[WebhookState]] is the server's internal representation of a webhook's state.
   */
  private[webhooks] sealed trait WebhookState extends Product with Serializable
  private[webhooks] object WebhookState {

    case object Disabled extends WebhookState

    @silent("never used")
    final case class Retrying private (
      retryQueue: Queue[WebhookEvent],
      backoffResets: Queue[Promise[Nothing, Unit]],
      base: Duration,
      power: Double,
      maxBackoff: Duration,
      timeout: Duration,
      activeSinceTime: Instant,
      lastRetryTime: Instant,
      failureCount: Int = 0,
      nextBackoff: Duration,
      timerKillSwitch: Option[Promise[Nothing, Unit]] = None,
      inFlight: Map[WebhookEventKey, WebhookEvent] = Map.empty,
      isActive: Boolean = false
    ) extends WebhookState {
      def addInFlight(events: Iterable[WebhookEvent]): Retrying    =
        copy(inFlight = inFlight ++ events.map(ev => ev.key -> ev))

      def enqueueAll(events: Iterable[WebhookEvent]): UIO[Boolean] =
        retryQueue.offerAll(events)

      /**
       * Progresses retrying to the next exponential backoff.
       */
      def increaseBackoff(timestamp: Instant): Retrying = {
        val nextExponential = base * math.pow(2, failureCount.toDouble)
        val nextBackoff     = if (nextExponential >= maxBackoff) maxBackoff else nextExponential
        val nextAttempt     = if (nextExponential >= maxBackoff) failureCount else failureCount + 1
        copy(lastRetryTime = timestamp, failureCount = nextAttempt, nextBackoff = nextBackoff)
      }

      def removeInFlight(events: Iterable[WebhookEvent]): Retrying =
        copy(inFlight = inFlight.removeAll(events.map(_.key)))

      def requeue(events: NonEmptyChunk[WebhookEvent]): URIO[Clock, Unit] =
        for {
          backoffReset <- Promise.make[Nothing, Unit]
          _            <- backoffResets.offer(backoffReset)
          _            <- clock.sleep(nextBackoff) race backoffReset.await
          _            <- retryQueue.offerAll(events)
        } yield ()

      /**
       * Reverts retry backoff to the initial state.
       */
      def resetBackoff(timestamp: Instant): Retrying =
        copy(lastRetryTime = timestamp, failureCount = 0, nextBackoff = base)

      /**
       * Activates a timer that calls an effect should the retrying state remain active past a
       * timeout duration. The timer is killed when retrying is set to inactive.
       */
      def setActiveWithTimeout[R, E](onTimeout: ZIO[R, E, Unit]): ZIO[R with Clock, E, Retrying] =
        if (isActive)
          UIO(this)
        else
          for {
            timerKillSwitch <- Promise.make[Nothing, Unit]
            runTimer         = timerKillSwitch.await.timeoutTo(false)(_ => true)(timeout).flatMap(onTimeout.unless(_))
            _               <- runTimer.fork
          } yield copy(timerKillSwitch = Some(timerKillSwitch), isActive = true)

      def setInactive: UIO[Retrying] =
        ZIO.foreach_(timerKillSwitch)(_.succeed(())).as(copy(timerKillSwitch = None, isActive = false))

      /**
       * Suspends this retry by replacing the backoff with the time left until its backoff completes.
       */
      def suspend(now: Instant): Retrying =
        copy(
          timeout = timeout.minus(Duration.fromInterval(activeSinceTime, now)),
          nextBackoff = nextBackoff.minus(JDuration.between(now, lastRetryTime))
        )
    }

    object Retrying {
      def make(retryConfig: WebhookServerConfig.Retry): URIO[Clock, Retrying] =
        ZIO.mapN(
          Queue.bounded[WebhookEvent](retryConfig.capacity),
          Queue.bounded[Promise[Nothing, Unit]](retryConfig.capacity),
          clock.instant
        )((retryQueue, backoffResetsQueue, timestamp) =>
          WebhookState.Retrying(
            retryQueue,
            backoffResetsQueue,
            retryConfig.exponentialBase,
            retryConfig.exponentialFactor,
            maxBackoff = retryConfig.maxBackoff,
            timeout = retryConfig.timeout,
            activeSinceTime = timestamp,
            lastRetryTime = timestamp,
            nextBackoff = retryConfig.exponentialBase
          )
        )
    }

    case object Unavailable extends WebhookState
  }
}
