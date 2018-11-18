package glutinous.rpc.netty

import javax.annotation.concurrent.GuardedBy

import glutinous.GlutinousException
import glutinous.rpc.{RpcAddress, RpcEndpoint, ThreadSafeRpcEndpoint}
import glutinous.util.Logging

import scala.util.control.NonFatal

private[netty] sealed trait InboxMessage

private[netty] case class OneWayMessage(senderAddress: RpcAddress, content: Any) extends InboxMessage

private[netty] case object OnStart extends InboxMessage

private[netty] case class RemoteProcessConnected(remoteAddress: RpcAddress) extends InboxMessage

private[netty] case class RpcMessage(
  senderAddress: RpcAddress,
  content: Any,
  context: NettyRpcCallContext) extends InboxMessage

class Inbox(val endpointRef: NettyRpcEndpointRef,
            val endpoint: RpcEndpoint) extends Logging {
  inbox =>

  protected val messages = new java.util.LinkedList[InboxMessage]()

  private var stopped = false

  private var enableConcurrent = false

  /** The number of threads processing messages for this inbox. */
  @GuardedBy("this")
  private var numActiveThreads = 0

  inbox.synchronized {
    messages.add(OnStart)
  }

  def post(message: InboxMessage): Unit = inbox.synchronized {
    if (stopped) {
      // We already put "OnStop" into "messages", so we should drop further messages
      onDrop(message)
    } else {
      messages.add(message)
      false
    }
  }

  protected def onDrop(message: InboxMessage): Unit = {
    warn(s"Drop $message because $endpointRef is stopped")
  }

  def process(dispatcher: Dispatcher): Unit = {

    var message: InboxMessage = null
    inbox.synchronized {
      if (!enableConcurrent && numActiveThreads != 0) {
        return
      }
      message = messages.poll()
      if (message != null) {
        numActiveThreads += 1
      } else {
        return
      }
    }
    while (true) {
      safelyCall(endpoint) {
        message match {
          case RpcMessage(_sender, content, context) =>
            try {
              endpoint.receiveAndReply(context).applyOrElse[Any, Unit](content, { msg =>
                throw new GlutinousException(s"Unsupported message $message from ${_sender}")
              })
            } catch {
              case NonFatal(e) =>
                context.sendFailure(e)
                // Throw the exception -- this exception will be caught by the safelyCall function.
                // The endpoint's onError function will be called.
                throw e
            }
          case OneWayMessage(_sender, content) =>
            endpoint.receive.applyOrElse[Any, Unit](content, { msg =>
              throw new GlutinousException(s"Unsupported message $message from ${_sender}")
            })
          case OnStart =>
            endpoint.onStart()
            if (!endpoint.isInstanceOf[ThreadSafeRpcEndpoint]) {
              inbox.synchronized {
                if (!stopped) {
                  enableConcurrent = true
                }
              }
            }
          case RemoteProcessConnected(remoteAddress) =>
            endpoint.onConnected(remoteAddress)
        }

        inbox.synchronized {
          // "enableConcurrent" will be set to false after `onStop` is called, so we should check it
          // every time.
          if (!enableConcurrent && numActiveThreads != 1) {
            // If we are not the only one worker, exit
            numActiveThreads -= 1
            return
          }
          message = messages.poll()
          if (message == null) {
            numActiveThreads -= 1
            return
          }
        }
      }
    }
    error("need to implement process")
  }


  private def safelyCall(endpoint: RpcEndpoint)(action: => Unit): Unit = {
    try action catch {
      case NonFatal(e) =>
        try endpoint.onError(e) catch {
          case NonFatal(ee) =>
            if (stopped) {
              debug("Ignoring error", ee)
            } else {
              error("Ignoring error", ee)
            }
        }
    }
  }
}
