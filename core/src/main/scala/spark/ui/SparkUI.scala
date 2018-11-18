/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spark.ui

import java.util.{Date, List => JList}

import spark.SparkConf
import spark.internal.Logging
import spark.SecurityManager

/**
 * Top level user interface for a Spark application.
 */
private[spark] class SparkUI private (
    val conf: SparkConf,
    securityManager: SecurityManager,
    var appName: String,
    val basePath: String,
    val startTime: Long,
    val appSparkVersion: String)
  extends WebUI(securityManager, securityManager.getSSLOptions("ui"), SparkUI.getUIPort(conf),
    conf, basePath, "SparkUI")
  with Logging
{

  var appId: String = _


  def getAppName: String = appName

  def setAppId(id: String): Unit = {
    appId = id
  }

  /** Stop the server behind this web interface. Only valid after bind(). */
  override def stop() {
    super.stop()
    logInfo(s"Stopped Spark web UI at $webUrl")
  }

  def withSparkUI[T](appId: String, attemptId: Option[String])(fn: SparkUI => T): T = {
    if (appId == this.appId) {
      fn(this)
    } else {
      throw new NoSuchElementException()
    }
  }

    /** A hook to initialize components of the UI */
    override def initialize(): Unit = ???
}

private[spark] abstract class SparkUITab(parent: SparkUI, prefix: String)
  extends WebUITab(parent, prefix) {

  def appName: String = parent.appName

  def appSparkVersion: String = parent.appSparkVersion
}

private[spark] object SparkUI {
  val DEFAULT_PORT = 4040
  val STATIC_RESOURCE_DIR = "spark/ui/static"
  val DEFAULT_POOL_NAME = "default"

  def getUIPort(conf: SparkConf): Int = {
    conf.getInt("spark.ui.port", SparkUI.DEFAULT_PORT)
  }

  /**
   * Create a new UI backed by an AppStatusStore.
   */
  def create(
      conf: SparkConf,
      securityManager: SecurityManager,
      appName: String,
      basePath: String,
      startTime: Long,
      appSparkVersion: String = spark.SPARK_VERSION): SparkUI = {

    new SparkUI(conf, securityManager, appName, basePath, startTime, appSparkVersion)
  }

}
