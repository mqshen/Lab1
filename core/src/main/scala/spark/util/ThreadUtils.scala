package spark.util

import java.util.concurrent._

import com.google.common.util.concurrent.{MoreExecutors, ThreadFactoryBuilder}
import spark.exception.SparkException

import scala.concurrent.{Awaitable, ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

object ThreadUtils {

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
  /**
    * Wrapper over newCachedThreadPool. Thread names are formatted as prefix-ID, where ID is a
    * unique, sequentially assigned integer.
    */
  def newDaemonCachedThreadPool(prefix: String): ThreadPoolExecutor = {
    val threadFactory = namedThreadFactory(prefix)
    Executors.newCachedThreadPool(threadFactory).asInstanceOf[ThreadPoolExecutor]
  }

  /**
    * Create a cached thread pool whose max number of threads is `maxThreadNumber`. Thread names
    * are formatted as prefix-ID, where ID is a unique, sequentially assigned integer.
    */
  def newDaemonCachedThreadPool(
                                 prefix: String, maxThreadNumber: Int, keepAliveSeconds: Int = 60): ThreadPoolExecutor = {
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

  private val sameThreadExecutionContext =
    ExecutionContext.fromExecutorService(MoreExecutors.sameThreadExecutor())

  /**
    * An `ExecutionContextExecutor` that runs each task in the thread that invokes `execute/submit`.
    * The caller should make sure the tasks running in this `ExecutionContextExecutor` are short and
    * never block.
    */
  def sameThread: ExecutionContextExecutor = sameThreadExecutionContext

  /**
    * Create a thread factory that names threads with a prefix and also sets the threads to daemon.
    */
  def namedThreadFactory(prefix: String): ThreadFactory = {
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat(prefix + "-%d").build()
  }
  /**
    * Wrapper over newFixedThreadPool. Thread names are formatted as prefix-ID, where ID is a
    * unique, sequentially assigned integer.
    */
  def newDaemonFixedThreadPool(nThreads: Int, prefix: String): ThreadPoolExecutor = {
    val threadFactory = namedThreadFactory(prefix)
    Executors.newFixedThreadPool(nThreads, threadFactory).asInstanceOf[ThreadPoolExecutor]
  }

  def awaitResult[T](awaitable: Awaitable[T], atMost: Duration): T = {
    try {
      // `awaitPermission` is not actually used anywhere so it's safe to pass in null here.
      // See SPARK-13747.
      val awaitPermission = null.asInstanceOf[scala.concurrent.CanAwait]
      awaitable.result(atMost)(awaitPermission)
    } catch {
      case e: SparkFatalException =>
        throw e.throwable
      // TimeoutException is thrown in the current thread, so not need to warp the exception.
      case NonFatal(t) if !t.isInstanceOf[TimeoutException] =>
        throw new SparkException("Exception thrown in awaitResult: ", t)
    }
  }

}
