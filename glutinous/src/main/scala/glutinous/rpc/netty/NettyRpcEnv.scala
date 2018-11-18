package glutinous.rpc.netty

import java.io.{DataInputStream, DataOutputStream, OutputStream}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, TimeUnit, TimeoutException}
import javax.annotation.Nullable

import glutinous.network.TransportContext
import glutinous.network.client.{TransportClient, TransportClientBootstrap}
import glutinous.network.server.{TransportServer, TransportServerBootstrap}
import glutinous.network.util.TransportConf
import glutinous.rpc._
import glutinous.serializer.{JavaSerializer, JavaSerializerInstance, SerializationStream}
import glutinous.util.{ByteBufferInputStream, ByteBufferOutputStream, Logging, ThreadUtils}

import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag
import scala.util.{DynamicVariable, Failure, Success}
import scala.util.control.NonFatal

class NettyRpcEnv(
  javaSerializerInstance: JavaSerializerInstance,
  host: String,
  numUsableCores: Int) extends RpcEnv with Logging {

  private def createClientBootstraps(): java.util.List[TransportClientBootstrap] = {
      java.util.Collections.emptyList[TransportClientBootstrap]
  }

  private val stopped = new AtomicBoolean(false)

  private val outboxes = new ConcurrentHashMap[RpcAddress, Outbox]()
  val timeoutScheduler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("netty-rpc-env-timeout")
  private[netty] val clientConnectionExecutor = ThreadUtils.newDaemonCachedThreadPool("netty-rpc-connection", 64)

  private val dispatcher: Dispatcher = new Dispatcher(this, numUsableCores)

  @volatile private var server: TransportServer = _
  private[netty] val transportConf = new TransportConf

  private val transportContext = new TransportContext(transportConf, new NettyRpcHandler(dispatcher, this))
  private val clientFactory = transportContext.createClientFactory(createClientBootstraps())

  def createClient(address: RpcAddress) = {
    clientFactory.createClient(address.host, address.port)
  }

  def startServer(bindAddress: String, port: Int): Unit = {
    val bootstraps: java.util.List[TransportServerBootstrap] = java.util.Collections.emptyList()
    server = transportContext.createServer(bindAddress, port, bootstraps)
//    dispatcher.registerRpcEndpoint(
//      RpcEndpointVerifier.NAME, new RpcEndpointVerifier(this, dispatcher))
  }

  def ask[T: ClassTag](message: RequestMessage, timeout: RpcTimeout): Future[T] = {
    val promise = Promise[Any]()
    val remoteAddr = message.receiver.address

    def onFailure(e: Throwable): Unit = {
      if (!promise.tryFailure(e)) {
        e match {
          //case e : RpcEnvStoppedException => (s"Ignored failure: $e")
          case _ => warn(s"Ignored failure: $e")
        }
      }
    }

    def onSuccess(reply: Any): Unit = reply match {
      case RpcFailure(e) => onFailure(e)
      case rpcReply =>
        if (!promise.trySuccess(rpcReply)) {
          warn(s"Ignored message: $reply")
        }
    }

    try {
      if (remoteAddr == address) {
        val p = Promise[Any]()
        p.future.onComplete {
          case Success(response) => onSuccess(response)
          case Failure(e) => onFailure(e)
        }(ThreadUtils.sameThread)
        dispatcher.postLocalMessage(message, p)
      } else {
        val rpcMessage = RpcOutboxMessage(message.serialize(this),
          onFailure,
          (client, response) => onSuccess(deserialize[Any](client, response)))
        postToOutbox(message.receiver, rpcMessage)
        promise.future.failed.foreach {
          case _: TimeoutException => rpcMessage.onTimeout()
          case _ =>
        }(ThreadUtils.sameThread)
      }

      val timeoutCancelable = timeoutScheduler.schedule(new Runnable {
        override def run(): Unit = {
          onFailure(new TimeoutException(s"Cannot receive any reply from ${remoteAddr} " +
            s"in ${timeout.duration}"))
        }
      }, timeout.duration.toNanos, TimeUnit.NANOSECONDS)
      promise.future.onComplete { v =>
        timeoutCancelable.cancel(true)
      }(ThreadUtils.sameThread)
    } catch {
      case NonFatal(e) =>
        onFailure(e)
    }
    promise.future.mapTo[T].recover(timeout.addMessageIfTimeout)(ThreadUtils.sameThread)
  }

  def send(message: RequestMessage): Unit = {
    val remoteAddr = message.receiver.address
    if (remoteAddr == address) {
      // Message to a local RPC endpoint.
      try {
        dispatcher.postOneWayMessage(message)
      } catch {
        case e: RpcEnvStoppedException => debug(e.getMessage)
      }
    } else {
      // Message to a remote RPC endpoint.
      postToOutbox(message.receiver, OneWayOutboxMessage(message.serialize(this)))
    }
  }

  private def postToOutbox(receiver: NettyRpcEndpointRef, message: OutboxMessage): Unit = {
    if (receiver.client != null) {
      message.sendWith(receiver.client)
    } else {
      require(receiver.address != null, "Cannot send message to client endpoint with no listen address.")
      val targetOutbox = {
        val outbox = outboxes.get(receiver.address)
        if (outbox == null) {
          val newOutbox = new Outbox(this, receiver.address)
          val oldOutbox = outboxes.putIfAbsent(receiver.address, newOutbox)
          if (oldOutbox == null) {
            newOutbox
          } else {
            oldOutbox
          }
        } else {
          outbox
        }
      }
      if (stopped.get) {
        // It's possible that we put `targetOutbox` after stopping. So we need to clean it.
        outboxes.remove(receiver.address)
        targetOutbox.stop()
      } else {
        targetOutbox.send(message)
      }
    }
  }

  def removeOutbox(address: RpcAddress) = {
    val outbox = outboxes.remove(address)
    if (outbox != null) {
      outbox.stop()
    }
  }

  private[netty] def deserialize[T: ClassTag](client: TransportClient, bytes: ByteBuffer): T = {
    NettyRpcEnv.currentClient.withValue(client) {
      deserialize { () =>
        javaSerializerInstance.deserialize[T](bytes)
      }
    }
  }

  private[netty] def serialize(content: Any): ByteBuffer = {
    javaSerializerInstance.serialize(content)
  }

  override def deserialize[T](deserializationAction: () => T): T = {
    NettyRpcEnv.currentEnv.withValue(this) {
      deserializationAction()
    }
  }

  /**
    * Register a [[RpcEndpoint]] with a name and return its [[RpcEndpointRef]]. [[RpcEnv]] does not
    * guarantee thread-safety.
    */
  override def setupEndpoint(name: String, endpoint: RpcEndpoint) = {
    dispatcher.registerRpcEndpoint(name, endpoint)
  }

  @Nullable
  override lazy val address: RpcAddress = {
    if (server != null) RpcAddress(host, server.getPort()) else null
  }

  override def awaitTermination(): Unit = dispatcher.awaitTermination()

  override private[rpc] def endpointRef(endpoint: RpcEndpoint) = dispatcher.getRpcEndpointRef(endpoint)

  override def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef] = {
    val addr = RpcEndpointAddress(uri)
    val endpointRef = new NettyRpcEndpointRef(addr, this)
    Future.successful(endpointRef)
  }

  private[netty] def serializeStream(out: OutputStream): SerializationStream = {
    javaSerializerInstance.serializeStream(out)
  }
}

private[netty] object NettyRpcEnv extends Logging {
  /**
    * When deserializing the [[NettyRpcEndpointRef]], it needs a reference to [[NettyRpcEnv]].
    * Use `currentEnv` to wrap the deserialization codes. E.g.,
    *
    * {{{
    *   NettyRpcEnv.currentEnv.withValue(this) {
    *     your deserialization codes
    *   }
    * }}}
    */
  private[netty] val currentEnv = new DynamicVariable[NettyRpcEnv](null)

  /**
    * Similar to `currentEnv`, this variable references the client instance associated with an
    * RPC, in case it's needed to find out the remote address during deserialization.
    */
  private[netty] val currentClient = new DynamicVariable[TransportClient](null)

}

case class RpcFailure(e: Throwable)

class NettyRpcEnvFactory extends RpcEnvFactory with Logging {

  override def create(config: RpcEnvConfig) = {
    val javaSerializerInstance = new JavaSerializer().newInstance().asInstanceOf[JavaSerializerInstance]
    val nettyEnv = new NettyRpcEnv(javaSerializerInstance, config.bindAddress, config.numUsableCores)
    if (!config.clientMode) {
      nettyEnv.startServer(config.bindAddress, config.port)
    }
    nettyEnv
  }

}

class RequestMessage(
  val senderAddress: RpcAddress,
  val receiver: NettyRpcEndpointRef,
  val content: Any) {

  def serialize(nettyEnv: NettyRpcEnv): ByteBuffer = {
    val bos = new ByteBufferOutputStream()
    val out = new DataOutputStream(bos)
    try {
      writeRpcAddress(out, senderAddress)
      writeRpcAddress(out, receiver.address)
      out.writeUTF(receiver.name)
      val s = nettyEnv.serializeStream(out)
      try {
        s.writeObject(content)
      } finally {
        s.close()
      }
    } finally {
      out.close()
    }
    bos.toByteBuffer
  }

  private def writeRpcAddress(out: DataOutputStream, rpcAddress: RpcAddress): Unit = {
    if (rpcAddress == null) {
      out.writeBoolean(false)
    } else {
      out.writeBoolean(true)
      out.writeUTF(rpcAddress.host)
      out.writeInt(rpcAddress.port)
    }
  }
}

private[netty] object RequestMessage {

  private def readRpcAddress(in: DataInputStream): RpcAddress = {
    val hasRpcAddress = in.readBoolean()
    if (hasRpcAddress) {
      RpcAddress(in.readUTF(), in.readInt())
    } else {
      null
    }
  }

  def apply(nettyEnv: NettyRpcEnv, client: TransportClient, bytes: ByteBuffer): RequestMessage = {
    val bis = new ByteBufferInputStream(bytes)
    val in = new DataInputStream(bis)
    try {
      val senderAddress = readRpcAddress(in)
      val endpointAddress = RpcEndpointAddress(readRpcAddress(in), in.readUTF())
      val ref = new NettyRpcEndpointRef(endpointAddress, nettyEnv)
      ref.client = client
      new RequestMessage(
        senderAddress,
        ref,
        // The remaining bytes in `bytes` are the message content.
        nettyEnv.deserialize(client, bytes))
    } finally {
      in.close()
    }
  }
}