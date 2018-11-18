package glutinous.rpc.netty

import java.nio.ByteBuffer
import java.util.concurrent.Callable

import glutinous.GlutinousException
import glutinous.network.client.{RpcResponseCallback, TransportClient}
import glutinous.rpc.{RpcAddress, RpcEnvStoppedException}
import glutinous.util.Logging

import scala.util.control.NonFatal

sealed trait OutboxMessage {

  def sendWith(client: TransportClient): Unit

  def onFailure(e: Throwable): Unit

}

private[netty] case class OneWayOutboxMessage(content: ByteBuffer) extends OutboxMessage
  with Logging {

  override def sendWith(client: TransportClient): Unit = {
    client.send(content)
  }

  override def onFailure(e: Throwable): Unit = {
    e match {
      case e1: RpcEnvStoppedException => debug(e1.getMessage)
      case e1: Throwable => warn(s"Failed to send one-way RPC.", e1)
    }
  }

}

class Outbox(nettyEnv: NettyRpcEnv, val address: RpcAddress) {
  outbox =>

  private val messages = new java.util.LinkedList[OutboxMessage]

  private var client: TransportClient = null

  private var connectFuture: java.util.concurrent.Future[Unit] = null

  private var stopped = false
  private var draining = false

  def send(message: OutboxMessage): Unit = {
    val dropped = synchronized {
      if (stopped) {
        true
      } else {
        messages.add(message)
        false
      }
    }
    if (dropped) {
      message.onFailure(new GlutinousException("Message is dropped because Outbox is stopped"))
    } else {
      drainOutbox()
    }
  }

  def launchConnectTask() = {
    connectFuture = nettyEnv.clientConnectionExecutor.submit(new Callable[Unit] {
      override def call(): Unit = {
        try {
          val _client = nettyEnv.createClient(address)
          outbox.synchronized {
            client = _client
            if (stopped) {
              closeClient()
            }
          }
        } catch {
          case ie: InterruptedException =>
            // exit
            return
          case NonFatal(e) =>
            outbox.synchronized { connectFuture = null }
            handleNetworkFailure(e)
            return
        }
        outbox.synchronized { connectFuture = null }
        // It's possible that no thread is draining now. If we don't drain here, we cannot send the
        // messages until the next message arrives.
        drainOutbox()
      }
    })
  }

  private def drainOutbox(): Unit = {
    var message: OutboxMessage = null
    synchronized {
      if (stopped) {
        return
      }
      if (connectFuture != null) {
        // We are connecting to the remote address, so just exit
        return
      }
      if (client == null) {
        // There is no connect task but client is null, so we need to launch the connect task.
        launchConnectTask()
        return
      }
      if (draining) {
        // There is some thread draining, so just exit
        return
      }
      message = messages.poll()
      if (message == null) {
        return
      }
      draining = true
    }
    while (true) {
      try {
        val _client = synchronized { client }
        if (_client != null) {
          message.sendWith(_client)
        } else {
          assert(stopped == true)
        }
      } catch {
        case NonFatal(e) =>
          handleNetworkFailure(e)
          return
      }
      synchronized {
        if (stopped) {
          return
        }
        message = messages.poll()
        if (message == null) {
          draining = false
          return
        }
      }
    }
  }

  def closeClient() = synchronized {
    // Just set client to null. Don't close it in order to reuse the connection.
    client = null
  }

  /**
    * Stop [[Inbox]] and notify the waiting messages with the cause.
    */
  private def handleNetworkFailure(e: Throwable): Unit = {
    synchronized {
      assert(connectFuture == null)
      if (stopped) {
        return
      }
      stopped = true
      closeClient()
    }
    // Remove this Outbox from nettyEnv so that the further messages will create a new Outbox along
    // with a new connection
    nettyEnv.removeOutbox(address)

    // Notify the connection failure for the remaining messages
    //
    // We always check `stopped` before updating messages, so here we can make sure no thread will
    // update messages and it's safe to just drain the queue.
    var message = messages.poll()
    while (message != null) {
      message.onFailure(e)
      message = messages.poll()
    }
    assert(messages.isEmpty)
  }

  def stop(): Unit = {
    synchronized {
      if (stopped) {
        return
      }
      stopped = true
      if (connectFuture != null) {
        connectFuture.cancel(true)
      }
      closeClient()
    }

    // We always check `stopped` before updating messages, so here we can make sure no thread will
    // update messages and it's safe to just drain the queue.
    var message = messages.poll()
    while (message != null) {
      message.onFailure(new GlutinousException("Message is dropped because Outbox is stopped"))
      message = messages.poll()
    }
  }

}


case class RpcOutboxMessage(
  content: ByteBuffer,
  _onFailure: (Throwable) => Unit,
  _onSuccess: (TransportClient, ByteBuffer) => Unit)
  extends OutboxMessage with RpcResponseCallback with Logging {

  private var client: TransportClient = _
  private var requestId: Long = _

  override def sendWith(client: TransportClient): Unit = {
    this.client = client
    //this.requestId = client.sendRpc(content, this)
    error("need to implement")
  }

  /**
    * Successful serialized result from server.
    *
    * After `onSuccess` returns, `response` will be recycled and its content will become invalid.
    * Please copy the content of `response` if you want to use it after `onSuccess` returns.
    */
  override def onSuccess(response: ByteBuffer): Unit = ???

  /** Exception either propagated from server or raised on client side. */
  override def onFailure(e: Throwable): Unit = ???

  def onTimeout(): Unit = {
    if (client != null) {
      //client.removeRpcRequest(requestId)
    } else {
      error("Ask timeout before connecting successfully")
    }
  }
}