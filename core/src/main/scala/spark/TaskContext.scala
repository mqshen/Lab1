package spark

import java.io.Serializable

object TaskContext {
  private[this] val taskContext: ThreadLocal[TaskContext] = new ThreadLocal[TaskContext]
  /**
    * Return the currently active TaskContext. This can be called inside of
    * user functions to access contextual information about running tasks.
    */
  def get(): TaskContext = taskContext.get

}

/**
  * Contextual information about a task which can be read or mutated during
  * execution. To access the TaskContext for a running task, use:
  * {{{
  *   spark.TaskContext.get()
  * }}}
  */
abstract class TaskContext extends Serializable {

  /** Marks the task as failed and triggers the failure listeners. */
  private[spark] def markTaskFailed(error: Throwable): Unit

}