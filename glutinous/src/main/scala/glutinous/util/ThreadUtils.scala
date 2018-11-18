package glutinous.util

import java.util.concurrent._

import com.google.common.util.concurrent.{MoreExecutors, ThreadFactoryBuilder}
import glutinous.GlutinousException

import scala.concurrent.{Awaitable, ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

object ThreadUtils {

  private val sameThreadExecutionContext =
    ExecutionContext.fromExecutorService(MoreExecutors.sameThreadExecutor())

  def namedThreadFactory(prefix: String): ThreadFactory = {
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat(prefix + "-%d").build()
  }
  /**
    * Wrapper over ScheduledThreadPoolExecutor.
    */
  def newDaemonSingleThreadScheduledExecutor(threadName: String): ScheduledExecutorService = {
    val threadFactory = new ThreadFactoryBuilder().setDaemon(true).setNameFormat(threadName).build()
    val executor = new ScheduledThreadPoolExecutor(1, threadFactory)
    // By default, a cancelled task is not automatically removed from the work queue until its delay
    // elapses. We have to enable it manually.
    executor.setRemoveOnCancelPolicy(true)
    executor
  }

  def newDaemonFixedThreadPool(nThreads: Int, prefix: String): ThreadPoolExecutor = {
    val threadFactory = namedThreadFactory(prefix)
    Executors.newFixedThreadPool(nThreads, threadFactory).asInstanceOf[ThreadPoolExecutor]
  }

  def newDaemonCachedThreadPool(prefix: String, maxThreadNumber: Int, keepAliveSeconds: Int = 60): ThreadPoolExecutor = {
    val threadFactory = namedThreadFactory(prefix)
    val threadPool = new ThreadPoolExecutor(
      maxThreadNumber, // corePoolSize: the max number of threads to create before queuing the tasks
      maxThreadNumber, // maximumPoolSize: because we use LinkedBlockingDeque, this one is not used
      keepAliveSeconds,
      TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable],
      threadFactory)
    threadPool.allowCoreThreadTimeOut(true)
    threadPool
  }

  /**
    * An `ExecutionContextExecutor` that runs each task in the thread that invokes `execute/submit`.
    * The caller should make sure the tasks running in this `ExecutionContextExecutor` are short and
    * never block.
    */
  def sameThread: ExecutionContextExecutor = sameThreadExecutionContext

  def awaitResult[T](awaitable: Awaitable[T], atMost: Duration): T = {
    try {
      val awaitPermission = null.asInstanceOf[scala.concurrent.CanAwait]
      awaitable.result(atMost)(awaitPermission)
    } catch {
      case e: GlutinousFatalException =>
        throw e.throwable
      case NonFatal(t) if !t.isInstanceOf[TimeoutException] =>
        throw new GlutinousException("Exception thrown in awaitResult: ", t)
    }
  }
}
