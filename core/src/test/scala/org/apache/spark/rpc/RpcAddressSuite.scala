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

package org.apache.spark.rpc

import org.apache.spark.{SparkException, SparkFunSuite}

class RpcAddressSuite extends SparkFunSuite {

  test("hostPort") {//主机端口
    val address = RpcAddress("1.2.3.4", 1234)
    assert(address.host == "1.2.3.4")
    assert(address.port == 1234)
    assert(address.hostPort == "1.2.3.4:1234")
  }

  test("fromSparkURL") {//来自Spark URL
    val address = RpcAddress.fromSparkURL("spark://1.2.3.4:1234")
    assert(address.host == "1.2.3.4")
    assert(address.port == 1234)
  }

  test("fromSparkURL: a typo url") {//来自一个错误Spark URL
    val e = intercept[SparkException] {
      RpcAddress.fromSparkURL("spark://1.2. 3.4:1234")
    }
    assert("Invalid master URL: spark://1.2. 3.4:1234" === e.getMessage)
  }

  test("fromSparkURL: invalid scheme") {//来自一个Spark URL无效模式
    val e = intercept[SparkException] {
      RpcAddress.fromSparkURL("invalid://1.2.3.4:1234")
    }
    assert("Invalid master URL: invalid://1.2.3.4:1234" === e.getMessage)
  }

  test("toSparkURL") {
    val address = RpcAddress("1.2.3.4", 1234)
    assert(address.toSparkURL == "spark://1.2.3.4:1234")
  }
}
