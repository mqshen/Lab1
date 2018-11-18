package glutinous.rpc


import java.util.concurrent.TimeoutException

import glutinous.util.ThreadUtils

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

private[rpc] class RpcTimeoutException(message: String, cause: TimeoutException)
  extends TimeoutException(message) { initCause(cause) }

class RpcTimeout(val duration: FiniteDuration) extends Serializable {
  private def createRpcTimeoutException(te: TimeoutException): RpcTimeoutException = {
    new RpcTimeoutException(te.getMessage + ". This timeout is controlled by " , te)
  }

  def awaitResult[T](future: Future[T]): T = {
    try {
      ThreadUtils.awaitResult(future, duration)
    } catch addMessageIfTimeout
  }

  /**
    * PartialFunction to match a TimeoutException and add the timeout description to the message
    *
    * @note This can be used in the recover callback of a Future to add to a TimeoutException
    * Example:
    *    val timeout = new RpcTimeout(5 millis, "short timeout")
    *    Future(throw new TimeoutException).recover(timeout.addMessageIfTimeout)
    */
  def addMessageIfTimeout[T]: PartialFunction[Throwable, T] = {
    // The exception has already been converted to a RpcTimeoutException so just raise it
    case rte: RpcTimeoutException => throw rte
    // Any other TimeoutException get converted to a RpcTimeoutException with modified message
    case te: TimeoutException => throw createRpcTimeoutException(te)
  }

}

object RpcTimeout {
  def apply(timeoutProp: String): RpcTimeout = {
    new RpcTimeout(120 seconds)
  }

}