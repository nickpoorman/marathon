package mesosphere.marathon.event.http

import javax.inject.{ Inject, Named }
import javax.servlet.ServletConfig
import javax.servlet.http.{ HttpServletResponse, HttpServlet, HttpServletRequest }

import akka.actor.ActorRef
import mesosphere.marathon.ModuleNames
import mesosphere.marathon.event.http.HttpEventStreamActor._
import org.eclipse.jetty.servlets.EventSource.Emitter
import org.eclipse.jetty.servlets.{ EventSource, EventSourceServlet }
import org.eclipse.jetty.websocket.WebSocket.Connection
import org.eclipse.jetty.websocket.{ WebSocket, WebSocketServlet }

/**
  * Handle a WebSocket client stream by delegating events to the stream actor.
  * @param request the initial http request.
  * @param httpStreamActor the stream actor.
  */
class HttpEventWebSocketHandle(request: HttpServletRequest, httpStreamActor: ActorRef)
    extends WebSocket.OnTextMessage with HttpEventStreamHandle {

  private var connection: Option[Connection] = None

  override def onMessage(data: String): Unit = {
    httpStreamActor ! HttpEventStreamIncomingMessage(this, data)
  }

  override def onOpen(connection: Connection): Unit = {
    this.connection = Some(connection)
    httpStreamActor ! HttpEventStreamConnectionOpen(this)
  }

  override def onClose(closeCode: Int, message: String): Unit = {
    httpStreamActor ! HttpEventStreamConnectionClosed(this)
    connection = None
  }

  def sendMessage(message: String): Unit = connection.foreach { conn =>
    if (conn.isOpen) conn.sendMessage(message)
  }

  override def connectionOpen: Boolean = connection.exists(_.isOpen)

  override def remoteAddress: String = request.getRemoteAddr

  override def toString: String = s"HttpEventSocketHandle($remoteAddress})"
}

/**
  * Handle a server side event client stream by delegating events to the stream actor.
  * @param request the initial http request.
  * @param httpStreamActor the stream actor.
  */
class HttpEventSSEHandle(request: HttpServletRequest, httpStreamActor: ActorRef)
    extends EventSource with HttpEventStreamHandle {

  private var emitter: Option[Emitter] = None

  override def onOpen(emitter: Emitter): Unit = {
    this.emitter = Some(emitter)
    httpStreamActor ! HttpEventStreamConnectionOpen(this)
  }

  override def onClose(): Unit = {
    httpStreamActor ! HttpEventStreamConnectionClosed(this)
    emitter = None
  }

  override def connectionOpen: Boolean = emitter.isDefined

  override def sendMessage(message: String): Unit = emitter.foreach(_.data(message))

  override def remoteAddress: String = request.getRemoteAddr

  override def toString: String = s"HttpEventSSEHandle($remoteAddress)"
}

class HttpEventStreamServlet @Inject() (@Named(ModuleNames.NAMED_HTTP_EVENT_STREAM) streamActor: ActorRef)
    extends HttpServlet {

  /**
    * The underlying web socket servlet to handle web socket requests.
    */
  val ws = new WebSocketServlet {
    override def doWebSocketConnect(request: HttpServletRequest, protocol: String): WebSocket = {
      new HttpEventWebSocketHandle(request, streamActor)
    }
  }

  /**
    * The underlying event source servlet to handle SSE requests.
    */
  val sse = new EventSourceServlet {
    override def newEventSource(request: HttpServletRequest): EventSource = {
      new HttpEventSSEHandle(request, streamActor)
    }
  }

  override def init(config: ServletConfig): Unit = {
    super.init(config)
    ws.init(config)
    sse.init(config)
  }

  /**
    * Content negotiation to delegate to the correct servlet.
    */
  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    if (req.getHeader("Upgrade") == "websocket") ws.service(req, resp)
    else if (req.getHeader("Accept") == "text/event-stream") sse.service(req, resp)
    else resp.sendError(406, "Either accept text/event-stream or provide WebSocket Upgrade option")
  }
}

