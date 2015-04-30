package mesosphere.marathon.event.http

import java.io.IOException

import akka.actor.Actor
import akka.event.EventStream
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.event.MarathonEvent
import org.apache.log4j.Logger
import play.api.libs.json.Json

class HttpEventStreamHandleActor(handle: HttpEventStreamHandle, stream: EventStream) extends Actor {

  private[this] val log = Logger.getLogger(getClass.getName)

  override def preStart(): Unit = {
    stream.subscribe(self, classOf[MarathonEvent])
  }

  override def postStop(): Unit = {
    stream.unsubscribe(self)
  }

  override def receive: Receive = {
    case event: MarathonEvent =>
      try {
        //this can cause an IOException
        handle.sendMessage(Json.stringify(eventToJson(event)))
      }
      catch {
        case ex: IOException => log.error(s"Could not send message to $handle", ex)
      }
  }
}

