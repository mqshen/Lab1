package spark.client

import java.nio.ByteBuffer

trait RpcResponseCallback {

  /**
    * Successful serialized result from server.
    *
    * After `onSuccess` returns, `response` will be recycled and its content will become invalid.
    * Please copy the content of `response` if you want to use it after `onSuccess` returns.
    */
  def onSuccess(response: ByteBuffer): Unit

  /** Exception either propagated from server or raised on client side. */
  def onFailure(e: Throwable): Unit

}
