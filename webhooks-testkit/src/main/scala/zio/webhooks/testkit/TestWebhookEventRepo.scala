package zio.webhooks.testkit

import zio._
import zio.stream.UStream
import zio.webhooks.WebhookError._
import zio.webhooks._

trait TestWebhookEventRepo {
  def createEvent(event: WebhookEvent): UIO[Unit]

  def subscribeToEvents: UManaged[Dequeue[WebhookEvent]]
}

object TestWebhookEventRepo {

  // Accessor Methods

  def createEvent(event: WebhookEvent): URIO[Has[TestWebhookEventRepo], Unit] =
    ZIO.serviceWith(_.createEvent(event))

  def subscribeToEvents: URManaged[Has[TestWebhookEventRepo], Dequeue[WebhookEvent]] =
    ZManaged.service[TestWebhookEventRepo].flatMap(_.subscribeToEvents)

  // Layer Definitions

  val test: ULayer[Has[WebhookEventRepo] with Has[TestWebhookEventRepo]] = {
    for {
      ref <- Ref.make(Map.empty[WebhookEventKey, WebhookEvent])
      hub <- Hub.unbounded[WebhookEvent]
      impl = TestWebhookEventRepoImpl(ref, hub)
    } yield Has.allOf[WebhookEventRepo, TestWebhookEventRepo](impl, impl)
  }.toLayerMany
}

final private case class TestWebhookEventRepoImpl(
  ref: Ref[Map[WebhookEventKey, WebhookEvent]],
  hub: Hub[WebhookEvent]
) extends WebhookEventRepo
    with TestWebhookEventRepo {

  def createEvent(event: WebhookEvent): UIO[Unit] =
    ref.update(_.updated(event.key, event)) <* hub.publish(event)

  private def getAllEvents: UIO[Chunk[WebhookEvent]] =
    ref.get.map(map => Chunk.fromIterable(map.values))

  def recoverEvents: UStream[WebhookEvent] =
    UStream.fromEffect(getAllEvents.map(events => UStream.fromChunk(events))).flatten

  def setAllAsFailedByWebhookId(webhookId: WebhookId): IO[MissingEventsError, Unit] =
    for {
      updatedMap <- ref.updateAndGet { map =>
                      map ++ (
                        for ((key, event) <- map if (key.webhookId == webhookId))
                          yield (key, event.copy(status = WebhookEventStatus.Failed))
                      )
                    }
      _          <- hub.publishAll(updatedMap.values)
    } yield ()

  def setEventStatus(key: WebhookEventKey, status: WebhookEventStatus): IO[MissingEventError, Unit] =
    for {
      eventOpt <- ref.modify { map =>
                    map.get(key) match {
                      case None        =>
                        (None, map)
                      case Some(event) =>
                        val updatedEvent = event.copy(status = status)
                        (Some(updatedEvent), map.updated(key, updatedEvent))
                    }
                  }
      _        <- eventOpt match {
                    case None        =>
                      ZIO.fail(MissingEventError(key))
                    case Some(event) =>
                      hub.publish(event).unit
                  }
    } yield ()

  def setEventStatusMany(
    keys: NonEmptyChunk[WebhookEventKey],
    status: WebhookEventStatus
  ): IO[MissingEventsError, Unit] =
    for {
      result <- ref.modify { map =>
                  val missingKeys = keys.filter(!map.contains(_))
                  if (missingKeys.nonEmpty)
                    (NonEmptyChunk.fromChunk(missingKeys).toLeft(Iterable.empty[WebhookEvent]), map)
                  else {
                    val updated =
                      for ((key, event) <- map if keys.contains(key))
                        yield (key, event.copy(status = status))
                    (Right(updated.values), map ++ updated)
                  }
                }
      _      <- result match {
                  case Left(missingKeys)    =>
                    ZIO.fail(MissingEventsError(missingKeys))
                  case Right(updatedEvents) =>
                    hub.publishAll(updatedEvents)
                }
    } yield ()

  def subscribeToEvents: UManaged[Dequeue[WebhookEvent]] =
    hub.subscribe

  def subscribeToNewEvents: UManaged[Dequeue[WebhookEvent]] =
    subscribeToEvents.map(_.filterOutput(_.isNew))
}
