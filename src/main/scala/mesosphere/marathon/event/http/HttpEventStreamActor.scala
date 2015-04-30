package mesosphere.marathon.event.http

import akka.actor.{ PoisonPill, ActorRef, Props, Actor }
import akka.event.EventStream
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
  //map from handle to actor
  private[http] var clients = Map.empty[HttpEventStreamHandle, ActorRef]
  private[this] val log = Logger.getLogger(getClass.getName)

  override def receive: Receive = {
    case HttpEventStreamConnectionOpen(handle)           => addHandler(handle)
    case HttpEventStreamConnectionClosed(handle)         => removeHandler(handle)
    case HttpEventStreamIncomingMessage(handle, message) => sendReply(handle, message)
  }

  def addHandler(handle: HttpEventStreamHandle): Unit = {
    log.info(s"Add EventStream Handle as event listener: $handle")
    val actor = context.actorOf(Props(classOf[HttpEventStreamHandleActor], handle, eventStream))
    clients += handle -> actor
    eventStream.publish(EventStreamAttached(handle.remoteAddress))
  }

  def removeHandler(handle: HttpEventStreamHandle): Unit = {
    log.info(s"Remove EventStream Handle as event listener: $handle")
    clients.get(handle).foreach { actor =>
      clients -= handle
      actor ! PoisonPill
      eventStream.publish(EventStreamDetached(handle.remoteAddress))
    }
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
