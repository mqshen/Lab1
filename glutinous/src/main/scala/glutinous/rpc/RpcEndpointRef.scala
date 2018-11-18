package glutinous.rpc

import scala.concurrent.Future
import scala.reflect.ClassTag

import scala.concurrent.duration._

abstract class RpcEndpointRef extends Serializable {

  private[this] val defaultAskTimeout = new RpcTimeout(30 seconds)//RpcUtils.askRpcTimeout(conf)


  def name: String
  /**
    * return the address for the [[RpcEndpointRef]]
    */
  def address: RpcAddress

  def send(message: Any): Unit
  /**
    * Send a message to the corresponding [[RpcEndpoint.receiveAndReply)]] and return a [[Future]] to
    * receive the reply within the specified timeout.
    *
    * This method only sends the message once and never retries.
    */
  def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T]

  def askSync[T: ClassTag](message: Any): T = askSync(message, defaultAskTimeout)

  /**
    * Send a message to the corresponding [[RpcEndpoint.receiveAndReply]] and get its result within a
    * specified timeout, throw an exception if this fails.
    *
    * Note: this is a blocking action which may cost a lot of time, so don't call it in a message
    * loop of [[RpcEndpoint]].
    *
    * @param message the message to send
    * @param timeout the timeout duration
    * @tparam T type of the reply message
    * @return the reply message from the corresponding [[RpcEndpoint]]
    */
  def askSync[T: ClassTag](message: Any, timeout: RpcTimeout): T = {
    val future = ask[T](message, timeout)
    timeout.awaitResult(future)
  }
}
