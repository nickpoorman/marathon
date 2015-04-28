package mesosphere.marathon.event.http

import akka.actor.Actor
import akka.event.EventStream
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.event._
import mesosphere.marathon.event.http.HttpEventStreamActor._
import org.apache.log4j.Logger
import play.api.libs.json.Json

/**
  * A HttpEventStreamHandle is a reference to the underlying client http stream.
  */
trait HttpEventStreamHandle {
  def connectionOpen: Boolean
  def remoteAddress: String
  def sendMessage(message: String): Unit
}

/**
  * This actor handles subscriptions from event stream handler.
  * It subscribes to the event stream and pushes all marathon events to all listener.
  * @param eventStream the marathon event stream
  */
class HttpEventStreamActor(eventStream: EventStream) extends Actor {
  private[this] var clients = Vector.empty[HttpEventStreamHandle]
  private[this] val log = Logger.getLogger(getClass.getName)

  override def preStart(): Unit = {
    eventStream.subscribe(self, classOf[MarathonEvent])
  }

  override def receive: Receive = {
    case event: MarathonEvent                            => broadcastEvent(event)
    case HttpEventStreamConnectionOpen(handle)           => addHandler(handle)
    case HttpEventStreamConnectionClosed(handle)         => removeHandler(handle)
    case HttpEventStreamIncomingMessage(handle, message) => sendReply(handle, message)
  }

  def broadcastEvent(event: MarathonEvent): Unit = {
    clients.foreach(_.sendMessage(Json.stringify(eventToJson(event))))
  }

  def addHandler(handle: HttpEventStreamHandle): Unit = {
    log.info(s"Add EventStream Handle as event listener: $handle")
    clients = handle +: clients
    eventStream.publish(EventStreamAttached(handle.remoteAddress))
  }

  def removeHandler(handle: HttpEventStreamHandle): Unit = {
    log.info(s"Remove EventStream Handle as event listener: $handle")
    clients = clients.filter(_ != handle)
    eventStream.publish(EventStreamDetached(handle.remoteAddress))
  }

  def sendReply(handle: HttpEventStreamHandle, message: String): Unit = {
    handle.sendMessage(Json.stringify(Json.obj("received" -> message)))
  }
}

object HttpEventStreamActor {
  case class HttpEventStreamConnectionOpen(handler: HttpEventStreamHandle)
  case class HttpEventStreamConnectionClosed(handle: HttpEventStreamHandle)
  case class HttpEventStreamIncomingMessage(handle: HttpEventStreamHandle, message: String)
}
