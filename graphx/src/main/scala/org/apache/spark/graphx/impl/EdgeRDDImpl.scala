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

package org.apache.spark.graphx.impl

import scala.reflect.{classTag, ClassTag}

import org.apache.spark.{OneToOneDependency, HashPartitioner}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import org.apache.spark.graphx._

class EdgeRDDImpl[ED: ClassTag, VD: ClassTag] private[graphx] (
    @transient override val partitionsRDD: RDD[(PartitionID, EdgePartition[ED, VD])],
    val targetStorageLevel: StorageLevel = StorageLevel.MEMORY_ONLY)
  extends EdgeRDD[ED](partitionsRDD.context, List(new OneToOneDependency(partitionsRDD))) {

  override def setName(_name: String): this.type = {
    if (partitionsRDD.name != null) {
      partitionsRDD.setName(partitionsRDD.name + ", " + _name)
    } else {
      partitionsRDD.setName(_name)
    }
    this
  }
  setName("EdgeRDD")

  /**
   * If `partitionsRDD` already has a partitioner, use it. Otherwise assume that the
   * [[PartitionID]]s in `partitionsRDD` correspond to the actual partitions and create a new
   * partitioner that allows co-partitioning with `partitionsRDD`.
    * 如果`partitionsRDD`已经有分区,请使用它,否则假设`partitionsRDD`中的[[PartitionID]]对应于实际分区,
    * 并创建一个允许与`partitionsRDD`共同分区的新分区器
   */
  override val partitioner =
    partitionsRDD.partitioner.orElse(Some(new HashPartitioner(partitions.size)))

  override def collect(): Array[Edge[ED]] = this.map(_.copy()).collect()

  /**
   * Persists the edge partitions at the specified storage level, ignoring any existing target
   * storage level.
    * 在指定的存储级别保留边缘分区,忽略任何现有的目标存储级别
   */
  override def persist(newLevel: StorageLevel): this.type = {
    partitionsRDD.persist(newLevel)
    this
  }

  override def unpersist(blocking: Boolean = true): this.type = {
    partitionsRDD.unpersist(blocking)
    this
  }

  /** Persists the edge partitions using `targetStorageLevel`, which defaults to MEMORY_ONLY.
    * 使用`targetStorageLevel`保留边缘分区,默认为MEMORY_ONLY */
  override def cache(): this.type = {
    partitionsRDD.persist(targetStorageLevel)
    this
  }

  override def getStorageLevel: StorageLevel = partitionsRDD.getStorageLevel

  override def checkpoint(): Unit = {
    partitionsRDD.checkpoint()
  }

  override def isCheckpointed: Boolean = {
    firstParent[(PartitionID, EdgePartition[ED, VD])].isCheckpointed
  }

  override def getCheckpointFile: Option[String] = {
    partitionsRDD.getCheckpointFile
  }

  /** The number of edges in the RDD.RDD中的边数 */
  override def count(): Long = {
    partitionsRDD.map(_._2.size.toLong).reduce(_ + _)
  }

  override def mapValues[ED2: ClassTag](f: Edge[ED] => ED2): EdgeRDDImpl[ED2, VD] =
    mapEdgePartitions((pid, part) => part.map(f))

  override def reverse: EdgeRDDImpl[ED, VD] = mapEdgePartitions((pid, part) => part.reverse)

  def filter(
      epred: EdgeTriplet[VD, ED] => Boolean,
      vpred: (VertexId, VD) => Boolean): EdgeRDDImpl[ED, VD] = {
    mapEdgePartitions((pid, part) => part.filter(epred, vpred))
  }

  override def innerJoin[ED2: ClassTag, ED3: ClassTag]
      (other: EdgeRDD[ED2])
      (f: (VertexId, VertexId, ED, ED2) => ED3): EdgeRDDImpl[ED3, VD] = {
    val ed2Tag = classTag[ED2]
    val ed3Tag = classTag[ED3]
    this.withPartitionsRDD[ED3, VD](partitionsRDD.zipPartitions(other.partitionsRDD, true) {
      (thisIter, otherIter) =>
        val (pid, thisEPart) = thisIter.next()
        val (_, otherEPart) = otherIter.next()
        Iterator(Tuple2(pid, thisEPart.innerJoin(otherEPart)(f)(ed2Tag, ed3Tag)))
    })
  }

  def mapEdgePartitions[ED2: ClassTag, VD2: ClassTag](
      f: (PartitionID, EdgePartition[ED, VD]) => EdgePartition[ED2, VD2]): EdgeRDDImpl[ED2, VD2] = {
    this.withPartitionsRDD[ED2, VD2](partitionsRDD.mapPartitions({ iter =>
      if (iter.hasNext) {
        val (pid, ep) = iter.next()
        Iterator(Tuple2(pid, f(pid, ep)))
      } else {
        Iterator.empty
      }
    }, preservesPartitioning = true))
  }

  private[graphx] def withPartitionsRDD[ED2: ClassTag, VD2: ClassTag](
      partitionsRDD: RDD[(PartitionID, EdgePartition[ED2, VD2])]): EdgeRDDImpl[ED2, VD2] = {
    new EdgeRDDImpl(partitionsRDD, this.targetStorageLevel)
  }

  override private[graphx] def withTargetStorageLevel(
      targetStorageLevel: StorageLevel): EdgeRDDImpl[ED, VD] = {
    new EdgeRDDImpl(this.partitionsRDD, targetStorageLevel)
  }

}
