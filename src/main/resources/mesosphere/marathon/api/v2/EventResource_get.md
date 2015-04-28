## GET `/v2/events`

Attach to the marathon event stream.
There are 2 options to access this stream: either using plain HTTP with Server Side Events or by using WebSockets. 

### Server Side Events

To use this endpoint, the client has to accept the text/event-stream content type.
Please note: a request to this endpoint will not be closed by the server.
If an event happens on the server side, this event will be propagated to the client immediately.
See [Server Side Events](http://www.w3schools.com/html/html5_serversentevents.asp) for a more detailed explanation.

**Request:**

```
GET /v2/events HTTP/1.1
Accept: text/event-stream
Accept-Encoding: gzip, deflate
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```
HTTP/1.1 200 OK
Cache-Control: no-cache, no-store, must-revalidate
Connection: close
Content-Type: text/event-stream;charset=UTF-8
Expires: 0
Pragma: no-cache
Server: Jetty(8.1.15.v20140411)

```

If an event happens on the server side, it is send as plain json prepended with the mandatory `data:` field.

**Response:**
```
data: {"clientIp":"0:0:0:0:0:0:0:1","eventType":"event_stream_attached","timestamp":"2015-04-28T12:14:57.812Z"}

data: {"groupId":"/","version":"2015-04-28T12:24:12.098Z","eventType":"group_change_success","timestamp":"2015-04-28T12:24:12.224Z"}
```


### WebSockets

To use this this endpoint, the client must be capable of handling WebSockets.
A WebSocket request has to provide the `Upgrade` HTTP header set to `websocket`.
As an upgrade takes place, the established connection remains open.
If an event happens on the server side, this event will be propagated to the client immediately.
See [WebSocket](https://www.websocket.org/aboutwebsocket.html) for a more detailed explanation.

**Request:**

```
GET /v2/events HTTP/1.1
Connection: Upgrade 
Host: 127.0.0.1:8080
Sec-WebSocket-Key: PP3o2sEu75ziLJ1GjpaZwQ==
Upgrade: websocket 
Sec-WebSocket-Version: 13
Cache-Control: no-cache
Pragma: no-cache
```


**Response:**

```
HTTP/1.1 101 WebSocket Protocol Handshake 
Connection: Upgrade 
Server: Jetty(8.1.15.v20140411)
Upgrade: WebSocket 
Sec-WebSocket-Accept: rLHCkw/SKsO9GAH/ZSFhBATDKrU= 

```

If an event happens on the server side, it is send as plain json.

**Response:**
```
{"clientIp":"0:0:0:0:0:0:0:1","eventType":"event_stream_attached","timestamp":"2015-04-28T12:14:57.812Z"}

{"groupId":"/","version":"2015-04-28T12:24:12.098Z","eventType":"group_change_success","timestamp":"2015-04-28T12:24:12.224Z"}
```


