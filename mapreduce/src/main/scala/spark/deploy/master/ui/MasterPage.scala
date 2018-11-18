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

package spark.deploy.master.ui

import javax.servlet.http.HttpServletRequest

import org.json4s.JsonAST.JValue
import spark.deploy.DeployMessages.{MasterStateResponse, RequestMasterState}
import spark.deploy.JsonProtocol
import spark.ui.{UIUtils, WebUIPage}


private[ui] class MasterPage(parent: MasterWebUI) extends WebUIPage("") {
  private val master = parent.masterEndpointRef

  def getMasterState: MasterStateResponse = {
    master.askSync[MasterStateResponse](RequestMasterState)
  }

  override def renderJson(request: HttpServletRequest): JValue = {
    JsonProtocol.writeMasterState(getMasterState)
  }

  override def render(request: HttpServletRequest) = {
    val state = getMasterState
    val content =
      <div class="row-fluid">
        <div class="span12">
          <ul class="unstyled">
            </ul>
          </div>
        </div>;
    UIUtils.basicSparkPage(request, content, "Spark Master at " + state.uri)
  }
}
