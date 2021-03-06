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

package org.apache.spark

import org.mockito.Mockito._
import org.scalatest.BeforeAndAfter
import org.scalatest.mock.MockitoSugar

import org.apache.spark.executor.{DataReadMethod, TaskMetrics}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage._

// TODO: Test the CacheManager's thread-safety aspects(测试线程安全方面)
class CacheManagerSuite extends SparkFunSuite with LocalSparkContext with BeforeAndAfter
  with MockitoSugar {

  var blockManager: BlockManager = _
  var cacheManager: CacheManager = _
  var split: Partition = _
  /** 
   *  An RDD which returns the values [1, 2, 3, 4].
   *  它返回RDD的值[1, 2, 3, 4]
   *   */
  var rdd: RDD[Int] = _
  var rdd2: RDD[Int] = _
  var rdd3: RDD[Int] = _

  before {
    sc = new SparkContext("local", "test")
    blockManager = mock[BlockManager]//模拟BlockManager
    //引用BlockManager
    cacheManager = new CacheManager(blockManager)
    
    split = new Partition { override def index: Int = 0 }
    rdd = new RDD[Int](sc, Nil) {
      override def getPartitions: Array[Partition] = Array(split)
      override val getDependencies = List[Dependency[_]]()//获得依赖关系
      override def compute(split: Partition, context: TaskContext): Iterator[Int] ={
        //println(split.index+"=="+context.taskMetrics().hostname);
        Array(1, 2, 3, 4).iterator//计算
      }
    }
    rdd2 = new RDD[Int](sc, List(new OneToOneDependency(rdd))) {//依赖RDD
      override def getPartitions: Array[Partition] = firstParent[Int].partitions
      override def compute(split: Partition, context: TaskContext): Iterator[Int] =
        firstParent[Int].iterator(split, context)
    }.cache()//缓存
    rdd3 = new RDD[Int](sc, List(new OneToOneDependency(rdd2))) {//依赖RDD1
      override def getPartitions: Array[Partition] = firstParent[Int].partitions
      override def compute(split: Partition, context: TaskContext): Iterator[Int] =
        firstParent[Int].iterator(split, context)
    }.cache()//缓存
  }

  test("get uncached rdd") {//得到未缓存的RDD
    // Do not mock this test, because attempting to match Array[Any], which is not covariant,
    // in blockManager.put is a losing battle(可能失败). You have been warned.
    //不要模拟这个测试,因为试图匹配数组[任何],这不是协变的,blockManager插入可能失败,你被警告了
    blockManager = sc.env.blockManager
    cacheManager = sc.env.cacheManager
    val context = TaskContext.empty()
    val computeValue = cacheManager.getOrCompute(rdd, split, context, StorageLevel.MEMORY_ONLY)
    val getValue = blockManager.get(RDDBlockId(rdd.id, split.index))
    assert(computeValue.toList === List(1, 2, 3, 4))//获得计算值
    //getValue BlockResult 
    //如果false,则块缓存从getorcompute没有被发现
    assert(getValue.isDefined, "Block cached from getOrCompute is not found!")
    assert(getValue.get.data.toList === List(1, 2, 3, 4))
  }

  test("get cached rdd") {//得到缓存的RDD
    val result = new BlockResult(Array(5, 6, 7).iterator, DataReadMethod.Memory, 12)
    when(blockManager.get(RDDBlockId(0, 0))).thenReturn(Some(result))//然后返回

    val context = TaskContext.empty()

    val getValue = blockManager.get(RDDBlockId(rdd.id, split.index))

    println(split.index+"==rddId=="+rdd.id+"==="+getValue.get)
    val value = cacheManager.getOrCompute(rdd, split, context, StorageLevel.MEMORY_ONLY)
    assert(value.toList === List(5, 6, 7))
  }

  test("get uncached local rdd") {//得到未被缓存的本地RDD
    // Local computation should not persist the resulting value, so don't expect a put().
    //本地计算产生的值不持久化,所以不期望一个插入
    when(blockManager.get(RDDBlockId(0, 0))).thenReturn(None)//然后返回

    val context = new TaskContextImpl(0, 0, 0, 0, null, null, Seq.empty, runningLocally = true)
    val value = cacheManager.getOrCompute(rdd, split, context, StorageLevel.MEMORY_ONLY)
    assert(value.toList === List(1, 2, 3, 4))
  }

  test("verify task metrics updated correctly") {//验证任务度量的正确更新
    cacheManager = sc.env.cacheManager
    val context = TaskContext.empty()
    cacheManager.getOrCompute(rdd3, split, context, StorageLevel.MEMORY_ONLY)
    assert(context.taskMetrics.updatedBlocks.getOrElse(Seq()).size === 2)
  }
}
