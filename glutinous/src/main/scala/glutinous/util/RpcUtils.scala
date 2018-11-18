package glutinous.util

import glutinous.rpc.RpcTimeout

object RpcUtils {

  /** Returns the default Spark timeout to use for RPC remote endpoint lookup. */
  def lookupRpcTimeout(): RpcTimeout = {
    RpcTimeout("120s")
  }

}
