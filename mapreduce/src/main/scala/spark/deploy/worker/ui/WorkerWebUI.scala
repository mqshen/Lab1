package spark.deploy.worker.ui

import java.io.File
import javax.servlet.http.HttpServletRequest

import spark.deploy.worker.Worker
import spark.internal.Logging
import spark.ui.{SparkUI, WebUI}
import spark.util.RpcUtils

/**
  * Web UI server for the standalone worker.
  */
private[worker]
class WorkerWebUI(
                   val worker: Worker,
                   val workDir: File,
                   requestedPort: Int)
  extends WebUI(worker.securityMgr, worker.securityMgr.getSSLOptions("standalone"),
    requestedPort, worker.conf, name = "WorkerUI")
    with Logging {

  private[ui] val timeout = RpcUtils.askRpcTimeout(worker.conf)

  initialize()

  /** Initialize all components of the server. */
  def initialize() {
//    val logPage = new LogPage(this)
//    attachPage(logPage)
//    attachPage(new WorkerPage(this))
//    addStaticHandler(WorkerWebUI.STATIC_RESOURCE_BASE)
//    attachHandler(createServletHandler("/log",
//      (request: HttpServletRequest) => logPage.renderLog(request),
//      worker.securityMgr,
//      worker.conf))
  }
}

private[worker] object WorkerWebUI {
  val STATIC_RESOURCE_BASE = SparkUI.STATIC_RESOURCE_DIR
  val DEFAULT_RETAINED_DRIVERS = 1000
  val DEFAULT_RETAINED_EXECUTORS = 1000
}
