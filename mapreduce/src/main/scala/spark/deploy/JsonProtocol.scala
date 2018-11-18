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

package spark.deploy

import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL._
import spark.deploy.DeployMessages.MasterStateResponse
import spark.deploy.master.WorkerInfo
import spark.master.ApplicationInfo

private[deploy] object JsonProtocol {
  /**
    * Export the [[WorkerInfo]] to a Json object. A [[WorkerInfo]] consists of the information of a
    * worker.
    *
    * @return a Json object containing the following fields:
    *         `id` a string identifier of the worker
    *         `host` the host that the worker is running on
    *         `port` the port that the worker is bound to
    *         `webuiaddress` the address used in web UI
    *         `cores` total cores of the worker
    *         `coresused` allocated cores of the worker
    *         `coresfree` free cores of the worker
    *         `memory` total memory of the worker
    *         `memoryused` allocated memory of the worker
    *         `memoryfree` free memory of the worker
    *         `state` state of the worker, see [[WorkerState]]
    *         `lastheartbeat` time in milliseconds that the latest heart beat message from the
    *         worker is received
    */
  def writeWorkerInfo(obj: WorkerInfo): JObject = {
    ("id" -> obj.id) ~
      ("host" -> obj.host) ~
      ("port" -> obj.port) ~
      ("webuiaddress" -> obj.webUiAddress) ~
      ("cores" -> obj.cores)
  }

  /**
    * Export the [[ApplicationInfo]] to a Json objec. An [[ApplicationInfo]] consists of the
    * information of an application.
    *
    * @return a Json object containing the following fields:
    *         `id` a string identifier of the application
    *         `starttime` time in milliseconds that the application starts
    *         `name` the description of the application
    *         `cores` total cores granted to the application
    *         `user` name of the user who submitted the application
    *         `memoryperslave` minimal memory in MB required to each executor
    *         `submitdate` time in Date that the application is submitted
    *         `state` state of the application, see [[ApplicationState]]
    *         `duration` time in milliseconds that the application has been running
    */
  def writeApplicationInfo(obj: ApplicationInfo): JObject = {
    ("id" -> obj.id) ~
      ("starttime" -> obj.startTime)
  }

  def writeMasterState(obj: MasterStateResponse): JObject = {
    ("url" -> obj.uri)
  }
}
