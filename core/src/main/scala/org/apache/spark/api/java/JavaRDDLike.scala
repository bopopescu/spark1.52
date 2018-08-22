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

package org.apache.spark.api.java

import java.{lang => jl}
import java.lang.{Iterable => JIterable, Long => JLong}
import java.util.{Comparator, List => JList, Iterator => JIterator}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import com.google.common.base.Optional
import org.apache.hadoop.io.compress.CompressionCodec

import org.apache.spark._
import org.apache.spark.annotation.Experimental
import org.apache.spark.api.java.JavaPairRDD._
import org.apache.spark.api.java.JavaSparkContext.fakeClassTag
import org.apache.spark.api.java.JavaUtils.mapAsSerializableJavaMap
import org.apache.spark.api.java.function.{Function => JFunction, Function2 => JFunction2, _}
import org.apache.spark.partial.{BoundedDouble, PartialResult}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils

/**
 * As a workaround for https://issues.scala-lang.org/browse/SI-8905, implementations
 * of JavaRDDLike should extend this dummy abstract class instead of directly inheriting
 * from the trait. See SPARK-3266 for additional details.
 */
private[spark] abstract class AbstractJavaRDDLike[T, This <: JavaRDDLike[T, This]]
  extends JavaRDDLike[T, This]

/**
 * Defines operations common to several Java RDD implementations.
 * Note that this trait is not intended to be implemented by user code.
 */
trait JavaRDDLike[T, This <: JavaRDDLike[T, This]] extends Serializable {
  def wrapRDD(rdd: RDD[T]): This

  implicit val classTag: ClassTag[T]

  def rdd: RDD[T]

  @deprecated("Use partitions() instead.", "1.1.0")
  def splits: JList[Partition] = new java.util.ArrayList(rdd.partitions.toSeq)

  /** Set of partitions in this RDD.
    * 此RDD中的分区集*/
  def partitions: JList[Partition] = new java.util.ArrayList(rdd.partitions.toSeq)

  /** The partitioner of this RDD.
    * 这个RDD的分区*/
  def partitioner: Optional[Partitioner] = JavaUtils.optionToOptional(rdd.partitioner)

  /** The [[org.apache.spark.SparkContext]] that this RDD was created on. */
  def context: SparkContext = rdd.context

  /** A unique ID for this RDD (within its SparkContext).
    * 此RDD的唯一ID（在其SparkContext中） */
  def id: Int = rdd.id

  /** Get the RDD's current storage level, or StorageLevel.NONE if none is set.
    * 获取RDD的当前存储级别,如果没有设置,则获取StorageLevel.NONE*/
  def getStorageLevel: StorageLevel = rdd.getStorageLevel

  /**
   * Internal method to this RDD; will read from cache if applicable, or otherwise compute it.
   * This should ''not'' be called by users directly, but is available for implementors of custom
   * subclasses of RDD.
   */
  def iterator(split: Partition, taskContext: TaskContext): java.util.Iterator[T] =
    asJavaIterator(rdd.iterator(split, taskContext))

  // Transformations (return a new RDD)

  /**
   * Return a new RDD by applying a function to all elements of this RDD.
    * 通过将函数应用于此RDD的所有元素来返回新的RDD
   */
  def map[R](f: JFunction[T, R]): JavaRDD[R] =
    new JavaRDD(rdd.map(f)(fakeClassTag))(fakeClassTag)

  /**
   * Return a new RDD by applying a function to each partition of this RDD, while tracking the index
   * of the original partition.
    * 通过将函数应用于此RDD的每个分区来返回新的RDD,同时跟踪原始分区的索引
   */
  def mapPartitionsWithIndex[R](
      f: JFunction2[jl.Integer, java.util.Iterator[T], java.util.Iterator[R]],
      preservesPartitioning: Boolean = false): JavaRDD[R] =
    new JavaRDD(rdd.mapPartitionsWithIndex(((a, b) => f(a, asJavaIterator(b))),
        preservesPartitioning)(fakeClassTag))(fakeClassTag)

  /**
   * Return a new RDD by applying a function to all elements of this RDD.
    * 通过将函数应用于此RDD的所有元素来返回新的RDD
   */
  def mapToDouble[R](f: DoubleFunction[T]): JavaDoubleRDD = {
    new JavaDoubleRDD(rdd.map(x => f.call(x).doubleValue()))
  }

  /**
   * Return a new RDD by applying a function to all elements of this RDD.
    * 通过将函数应用于此RDD的所有元素来返回新的RDD
   */
  def mapToPair[K2, V2](f: PairFunction[T, K2, V2]): JavaPairRDD[K2, V2] = {
    def cm: ClassTag[(K2, V2)] = implicitly[ClassTag[(K2, V2)]]
    new JavaPairRDD(rdd.map[(K2, V2)](f)(cm))(fakeClassTag[K2], fakeClassTag[V2])
  }

  /**
   *  Return a new RDD by first applying a function to all elements of this
   *  RDD, and then flattening the results.
    *  通过首先将函数应用于此RDD的所有元素,然后展平结果,返回新的RDD
   */
  def flatMap[U](f: FlatMapFunction[T, U]): JavaRDD[U] = {
    import scala.collection.JavaConverters._
    def fn: (T) => Iterable[U] = (x: T) => f.call(x).asScala
    JavaRDD.fromRDD(rdd.flatMap(fn)(fakeClassTag[U]))(fakeClassTag[U])
  }

  /**
   *  Return a new RDD by first applying a function to all elements of this
   *  RDD, and then flattening the results.
    *  通过首先将函数应用于此RDD的所有元素,然后展平结果,返回新的RDD。
   */
  def flatMapToDouble(f: DoubleFlatMapFunction[T]): JavaDoubleRDD = {
    import scala.collection.JavaConverters._
    def fn: (T) => Iterable[jl.Double] = (x: T) => f.call(x).asScala
    new JavaDoubleRDD(rdd.flatMap(fn).map((x: jl.Double) => x.doubleValue()))
  }

  /**
   *  Return a new RDD by first applying a function to all elements of this
   *  RDD, and then flattening the results.
    *  通过首先将函数应用于此RDD的所有元素,然后展平结果,返回新的RDD
   */
  def flatMapToPair[K2, V2](f: PairFlatMapFunction[T, K2, V2]): JavaPairRDD[K2, V2] = {
    import scala.collection.JavaConverters._
    def fn: (T) => Iterable[(K2, V2)] = (x: T) => f.call(x).asScala
    def cm: ClassTag[(K2, V2)] = implicitly[ClassTag[(K2, V2)]]
    JavaPairRDD.fromRDD(rdd.flatMap(fn)(cm))(fakeClassTag[K2], fakeClassTag[V2])
  }

  /**
   * Return a new RDD by applying a function to each partition of this RDD.
    * 通过将函数应用于此RDD的每个分区来返回新的RDD
   */
  def mapPartitions[U](f: FlatMapFunction[java.util.Iterator[T], U]): JavaRDD[U] = {
    def fn: (Iterator[T]) => Iterator[U] = {
      (x: Iterator[T]) => asScalaIterator(f.call(asJavaIterator(x)).iterator())
    }
    JavaRDD.fromRDD(rdd.mapPartitions(fn)(fakeClassTag[U]))(fakeClassTag[U])
  }

  /**
   * Return a new RDD by applying a function to each partition of this RDD.
    * 通过将函数应用于此RDD的每个分区来返回新的RDD
   */
  def mapPartitions[U](f: FlatMapFunction[java.util.Iterator[T], U],
      preservesPartitioning: Boolean): JavaRDD[U] = {
    def fn: (Iterator[T]) => Iterator[U] = {
      (x: Iterator[T]) => asScalaIterator(f.call(asJavaIterator(x)).iterator())
    }
    JavaRDD.fromRDD(
      rdd.mapPartitions(fn, preservesPartitioning)(fakeClassTag[U]))(fakeClassTag[U])
  }

  /**
   * Return a new RDD by applying a function to each partition of this RDD.
    * 通过将函数应用于此RDD的每个分区来返回新的RDD
   */
  def mapPartitionsToDouble(f: DoubleFlatMapFunction[java.util.Iterator[T]]): JavaDoubleRDD = {
    def fn: (Iterator[T]) => Iterator[jl.Double] = {
      (x: Iterator[T]) => asScalaIterator(f.call(asJavaIterator(x)).iterator())
    }
    new JavaDoubleRDD(rdd.mapPartitions(fn).map((x: jl.Double) => x.doubleValue()))
  }

  /**
   * Return a new RDD by applying a function to each partition of this RDD.
    * 通过将函数应用于此RDD的每个分区来返回新的RDD
   */
  def mapPartitionsToPair[K2, V2](f: PairFlatMapFunction[java.util.Iterator[T], K2, V2]):
  JavaPairRDD[K2, V2] = {
    def fn: (Iterator[T]) => Iterator[(K2, V2)] = {
      (x: Iterator[T]) => asScalaIterator(f.call(asJavaIterator(x)).iterator())
    }
    JavaPairRDD.fromRDD(rdd.mapPartitions(fn))(fakeClassTag[K2], fakeClassTag[V2])
  }

  /**
   * Return a new RDD by applying a function to each partition of this RDD.
    * 通过将函数应用于此RDD的每个分区来返回新的RDD
   */
  def mapPartitionsToDouble(f: DoubleFlatMapFunction[java.util.Iterator[T]],
      preservesPartitioning: Boolean): JavaDoubleRDD = {
    def fn: (Iterator[T]) => Iterator[jl.Double] = {
      (x: Iterator[T]) => asScalaIterator(f.call(asJavaIterator(x)).iterator())
    }
    new JavaDoubleRDD(rdd.mapPartitions(fn, preservesPartitioning)
      .map(x => x.doubleValue()))
  }

  /**
   * Return a new RDD by applying a function to each partition of this RDD.
    * 通过将函数应用于此RDD的每个分区来返回新的RDD
   */
  def mapPartitionsToPair[K2, V2](f: PairFlatMapFunction[java.util.Iterator[T], K2, V2],
      preservesPartitioning: Boolean): JavaPairRDD[K2, V2] = {
    def fn: (Iterator[T]) => Iterator[(K2, V2)] = {
      (x: Iterator[T]) => asScalaIterator(f.call(asJavaIterator(x)).iterator())
    }
    JavaPairRDD.fromRDD(
      rdd.mapPartitions(fn, preservesPartitioning))(fakeClassTag[K2], fakeClassTag[V2])
  }

  /**
   * Applies a function f to each partition of this RDD.
    * 将函数f应用于此RDD的每个分区
   */
  def foreachPartition(f: VoidFunction[java.util.Iterator[T]]) {
    rdd.foreachPartition((x => f.call(asJavaIterator(x))))
  }

  /**
   * Return an RDD created by coalescing all elements within each partition into an array.
    * 返回通过将每个分区中的所有元素合并为数组而创建的RDD
   */
  def glom(): JavaRDD[JList[T]] =
    new JavaRDD(rdd.glom().map(x => new java.util.ArrayList[T](x.toSeq)))

  /**
   * Return the Cartesian product of this RDD and another one, that is, the RDD of all pairs of
   * elements (a, b) where a is in `this` and b is in `other`.
    * 返回此RDD的笛卡尔乘积和另一个,即所有元素对(a,b)的RDD,其中a在“this”中,b在“other”中
   */
  def cartesian[U](other: JavaRDDLike[U, _]): JavaPairRDD[T, U] =
    JavaPairRDD.fromRDD(rdd.cartesian(other.rdd)(other.classTag))(classTag, other.classTag)

  /**
   * Return an RDD of grouped elements. Each group consists of a key and a sequence of elements
   * mapping to that key.
    * 返回分组元素的RDD,每个组由一个键和一系列映射到该键的元素组成
   */
  def groupBy[U](f: JFunction[T, U]): JavaPairRDD[U, JIterable[T]] = {
    // The type parameter is U instead of K in order to work around a compiler bug; see SPARK-4459
    implicit val ctagK: ClassTag[U] = fakeClassTag
    implicit val ctagV: ClassTag[JList[T]] = fakeClassTag
    JavaPairRDD.fromRDD(groupByResultToJava(rdd.groupBy(f)(fakeClassTag)))
  }

  /**
   * Return an RDD of grouped elements. Each group consists of a key and a sequence of elements
   * mapping to that key.
    * 返回分组元素的RDD,每个组由一个键和一系列映射到该键的元素组成。
   */
  def groupBy[U](f: JFunction[T, U], numPartitions: Int): JavaPairRDD[U, JIterable[T]] = {
    // The type parameter is U instead of K in order to work around a compiler bug; see SPARK-4459
    implicit val ctagK: ClassTag[U] = fakeClassTag
    implicit val ctagV: ClassTag[JList[T]] = fakeClassTag
    JavaPairRDD.fromRDD(groupByResultToJava(rdd.groupBy(f, numPartitions)(fakeClassTag[U])))
  }

  /**
   * Return an RDD created by piping elements to a forked external process.
    * 将由管道元素创建的RDD返回到分叉的外部进程
   */
  def pipe(command: String): JavaRDD[String] = rdd.pipe(command)

  /**
   * Return an RDD created by piping elements to a forked external process.
    * 将由管道元素创建的RDD返回到分叉的外部进程
   */
  def pipe(command: JList[String]): JavaRDD[String] =
    rdd.pipe(asScalaBuffer(command))

  /**
   * Return an RDD created by piping elements to a forked external process.
    * 将由管道元素创建的RDD返回到分叉的外部进程
   */
  def pipe(command: JList[String], env: java.util.Map[String, String]): JavaRDD[String] =
    rdd.pipe(asScalaBuffer(command), mapAsScalaMap(env))

  /**
   * Zips this RDD with another one, returning key-value pairs with the first element in each RDD,
    * 将此RDD与另一个RDD一起使用,返回每个RDD中第一个元素的键值对
   * second element in each RDD, etc. Assumes that the two RDDs have the *same number of
   * partitions* and the *same number of elements in each partition* (e.g. one was made through
   * a map on the other).
    * 用另一个RDD压缩此RDD,返回键值对,每个RDD中的第一个元素,每个RDD中的第二个元素等等。
    * 假设两个RDD具有*相同数量的分区*和每个RDD中相同数量的元素分区*(例如,一个是通过*另一个地图制作的)。
   */
  def zip[U](other: JavaRDDLike[U, _]): JavaPairRDD[T, U] = {
    JavaPairRDD.fromRDD(rdd.zip(other.rdd)(other.classTag))(classTag, other.classTag)
  }

  /**
   * Zip this RDD's partitions with one (or more) RDD(s) and return a new RDD by
   * applying a function to the zipped partitions. Assumes that all the RDDs have the
   * *same number of partitions*, but does *not* require them to have the same number
   * of elements in each partition.
    *
    * 使用一个(或多个)RDD将此RDD的分区压缩,并通过将函数应用于压缩分区来返回新的RDD,
    * 假设所有RDD具有*相同数量的分区*,但*不要求它们在每个分区中具有相同数量的元素。
    *
   */
  def zipPartitions[U, V](
      other: JavaRDDLike[U, _],
      f: FlatMapFunction2[java.util.Iterator[T], java.util.Iterator[U], V]): JavaRDD[V] = {
    def fn: (Iterator[T], Iterator[U]) => Iterator[V] = {
      (x: Iterator[T], y: Iterator[U]) => asScalaIterator(
        f.call(asJavaIterator(x), asJavaIterator(y)).iterator())
    }
    JavaRDD.fromRDD(
      rdd.zipPartitions(other.rdd)(fn)(other.classTag, fakeClassTag[V]))(fakeClassTag[V])
  }

  /**
   * Zips this RDD with generated unique Long ids. Items in the kth partition will get ids k, n+k,
   * 2*n+k, ..., where n is the number of partitions. So there may exist gaps, but this method
   * won't trigger a spark job, which is different from [[org.apache.spark.rdd.RDD#zipWithIndex]].
   */
  def zipWithUniqueId(): JavaPairRDD[T, JLong] = {
    JavaPairRDD.fromRDD(rdd.zipWithUniqueId()).asInstanceOf[JavaPairRDD[T, JLong]]
  }

  /**
   * Zips this RDD with its element indices. The ordering is first based on the partition index
   * and then the ordering of items within each partition. So the first item in the first
   * partition gets index 0, and the last item in the last partition receives the largest index.
   * This is similar to Scala's zipWithIndex but it uses Long instead of Int as the index type.
   * This method needs to trigger a spark job when this RDD contains more than one partitions.
   */
  def zipWithIndex(): JavaPairRDD[T, JLong] = {
    JavaPairRDD.fromRDD(rdd.zipWithIndex()).asInstanceOf[JavaPairRDD[T, JLong]]
  }

  // Actions (launch a job to return a value to the user program)

  /**
   * Applies a function f to all elements of this RDD.
    * 将函数f应用于此RDD的所有元素
   */
  def foreach(f: VoidFunction[T]) {
    rdd.foreach(x => f.call(x))
  }

  /**
   * Return an array that contains all of the elements in this RDD.
    * 返回包含此RDD中所有元素的数组
   */
  def collect(): JList[T] = {
    import scala.collection.JavaConversions._
    val arr: java.util.Collection[T] = rdd.collect().toSeq
    new java.util.ArrayList(arr)
  }

  /**
   * Return an iterator that contains all of the elements in this RDD.
   * 返回包含此RDD中所有元素的迭代器
   * The iterator will consume as much memory as t
    * he largest partition in this RDD.
    * 迭代器将消耗与此RDD中最大分区一样多的内存
   */
  def toLocalIterator(): JIterator[T] = {
     import scala.collection.JavaConversions._
     rdd.toLocalIterator
  }


  /**
   * Return an array that contains all of the elements in this RDD.
    * 返回包含此RDD中所有元素的数组
   * @deprecated As of Spark 1.0.0, toArray() is deprecated, use {@link #collect()} instead
   */
  @deprecated("use collect()", "1.0.0")
  def toArray(): JList[T] = collect()

  /**
   * Return an array that contains all of the elements in a specific partition of this RDD.
    * 返回一个数组，其中包含此RDD的特定分区中的所有元素
   */
  def collectPartitions(partitionIds: Array[Int]): Array[JList[T]] = {
    // This is useful for implementing `take` from other language frontends
    // like Python where the data is serialized.
    import scala.collection.JavaConversions._
    val res = context.runJob(rdd, (it: Iterator[T]) => it.toArray, partitionIds)
    res.map(x => new java.util.ArrayList(x.toSeq)).toArray
  }

  /**
   * Reduces the elements of this RDD using the specified commutative and associative binary
   * operator.
    * 使用指定的可交换和关联二元运算符减少此RDD的元素
   */
  def reduce(f: JFunction2[T, T, T]): T = rdd.reduce(f)

  /**
   * Reduces the elements of this RDD in a multi-level tree pattern.
    * 在多级树模式中减少此RDD的元素
   *
   * @param depth suggested depth of the tree
   * @see [[org.apache.spark.api.java.JavaRDDLike#reduce]]
   */
  def treeReduce(f: JFunction2[T, T, T], depth: Int): T = rdd.treeReduce(f, depth)

  /**
   * [[org.apache.spark.api.java.JavaRDDLike#treeReduce]] with suggested depth 2.
   */
  def treeReduce(f: JFunction2[T, T, T]): T = treeReduce(f, 2)

  /**
   * Aggregate the elements of each partition, and then the results for all the partitions, using a
   * given associative and commutative function and a neutral "zero value". The function
   * op(t1, t2) is allowed to modify t1 and return it as its result value to avoid object
   * allocation; however, it should not modify t2.
   *
   * This behaves somewhat differently from fold operations implemented for non-distributed
   * collections in functional languages like Scala. This fold operation may be applied to
   * partitions individually, and then fold those results into the final result, rather than
   * apply the fold to each element sequentially in some defined ordering. For functions
   * that are not commutative, the result may differ from that of a fold applied to a
   * non-distributed collection.
   */
  def fold(zeroValue: T)(f: JFunction2[T, T, T]): T =
    rdd.fold(zeroValue)(f)

  /**
   * Aggregate the elements of each partition, and then the results for all the partitions, using
   * given combine functions and a neutral "zero value". This function can return a different result
   * type, U, than the type of this RDD, T. Thus, we need one operation for merging a T into an U
   * and one operation for merging two U's, as in scala.TraversableOnce. Both of these functions are
   * allowed to modify and return their first argument instead of creating a new U to avoid memory
   * allocation.
   */
  def aggregate[U](zeroValue: U)(seqOp: JFunction2[U, T, U],
    combOp: JFunction2[U, U, U]): U =
    rdd.aggregate(zeroValue)(seqOp, combOp)(fakeClassTag[U])

  /**
   * Aggregates the elements of this RDD in a multi-level tree pattern.
   * 以多级树模式聚合此RDD的元素
   * @param depth suggested depth of the tree
   * @see [[org.apache.spark.api.java.JavaRDDLike#aggregate]]
   */
  def treeAggregate[U](
      zeroValue: U,
      seqOp: JFunction2[U, T, U],
      combOp: JFunction2[U, U, U],
      depth: Int): U = {
    rdd.treeAggregate(zeroValue)(seqOp, combOp, depth)(fakeClassTag[U])
  }

  /**
   * [[org.apache.spark.api.java.JavaRDDLike#treeAggregate]] with suggested depth 2.
   */
  def treeAggregate[U](
      zeroValue: U,
      seqOp: JFunction2[U, T, U],
      combOp: JFunction2[U, U, U]): U = {
    treeAggregate(zeroValue, seqOp, combOp, 2)
  }

  /**
   * Return the number of elements in the RDD.
    * 返回RDD中的元素数
   */
  def count(): Long = rdd.count()

  /**
   * :: Experimental ::
   * Approximate version of count() that returns a potentially incomplete result
   * within a timeout, even if not all tasks have finished.
   */
  @Experimental
  def countApprox(timeout: Long, confidence: Double): PartialResult[BoundedDouble] =
    rdd.countApprox(timeout, confidence)

  /**
   * :: Experimental ::
   * Approximate version of count() that returns a potentially incomplete result
   * within a timeout, even if not all tasks have finished.
   */
  @Experimental
  def countApprox(timeout: Long): PartialResult[BoundedDouble] =
    rdd.countApprox(timeout)

  /**
   * Return the count of each unique value in this RDD as a map of (value, count) pairs. The final
   * combine step happens locally on the master, equivalent to running a single reduce task.
    * 返回此RDD中每个唯一值的计数作为(值，计数)对的映射,
    * 最终组合步骤在主服务器上本地发生,相当于运行单个reduce任务。
   */
  def countByValue(): java.util.Map[T, jl.Long] =
    mapAsSerializableJavaMap(rdd.countByValue().map((x => (x._1, new jl.Long(x._2)))))

  /**
   * (Experimental) Approximate version of countByValue().
   */
  def countByValueApprox(
    timeout: Long,
    confidence: Double
    ): PartialResult[java.util.Map[T, BoundedDouble]] =
    rdd.countByValueApprox(timeout, confidence).map(mapAsSerializableJavaMap)

  /**
   * (Experimental) Approximate version of countByValue().
   */
  def countByValueApprox(timeout: Long): PartialResult[java.util.Map[T, BoundedDouble]] =
    rdd.countByValueApprox(timeout).map(mapAsSerializableJavaMap)

  /**
   * Take the first num elements of the RDD. This currently scans the partitions *one by one*, so
   * it will be slow if a lot of partitions are required. In that case, use collect() to get the
   * whole RDD instead.
   */
  def take(num: Int): JList[T] = {
    import scala.collection.JavaConversions._
    val arr: java.util.Collection[T] = rdd.take(num).toSeq
    new java.util.ArrayList(arr)
  }

  def takeSample(withReplacement: Boolean, num: Int): JList[T] =
    takeSample(withReplacement, num, Utils.random.nextLong)

  def takeSample(withReplacement: Boolean, num: Int, seed: Long): JList[T] = {
    import scala.collection.JavaConversions._
    val arr: java.util.Collection[T] = rdd.takeSample(withReplacement, num, seed).toSeq
    new java.util.ArrayList(arr)
  }

  /**
   * Return the first element in this RDD.
    * 返回此RDD中的第一个元素
   */
  def first(): T = rdd.first()

  /**
   * @return true if and only if the RDD contains no elements at all. Note that an RDD
   *         may be empty even when it has at least 1 partition.
   */
  def isEmpty(): Boolean = rdd.isEmpty()

  /**
   * Save this RDD as a text file, using string representations of elements.
    * 使用元素的字符串表示将此RDD保存为文本文件
   */
  def saveAsTextFile(path: String): Unit = {
    rdd.saveAsTextFile(path)
  }


  /**
   * Save this RDD as a compressed text file, using string representations of elements.
    * 使用元素的字符串表示将此RDD保存为压缩文本文件
   */
  def saveAsTextFile(path: String, codec: Class[_ <: CompressionCodec]): Unit = {
    rdd.saveAsTextFile(path, codec)
  }

  /**
   * Save this RDD as a SequenceFile of serialized objects.
    * 将此RDD保存为序列化对象的SequenceFile
   */
  def saveAsObjectFile(path: String): Unit = {
    rdd.saveAsObjectFile(path)
  }

  /**
   * Creates tuples of the elements in this RDD by applying `f`.
    * 通过应用`f`创建此RDD中元素的元组
   */
  def keyBy[U](f: JFunction[T, U]): JavaPairRDD[U, T] = {
    // The type parameter is U instead of K in order to work around a compiler bug; see SPARK-4459
    implicit val ctag: ClassTag[U] = fakeClassTag
    JavaPairRDD.fromRDD(rdd.keyBy(f))
  }

  /**
   * Mark this RDD for checkpointing. It will be saved to a file inside the checkpoint
   * directory set with SparkContext.setCheckpointDir() and all references to its parent
   * RDDs will be removed. This function must be called before any job has been
   * executed on this RDD. It is strongly recommended that this RDD is persisted in
   * memory, otherwise saving it on a file will require recomputation.
   */
  def checkpoint(): Unit = {
    rdd.checkpoint()
  }

  /**
   * Return whether this RDD has been checkpointed or not
    * 返回此RDD是否已经过检查点
   */
  def isCheckpointed: Boolean = rdd.isCheckpointed

  /**
   * Gets the name of the file to which this RDD was checkpointed
    * 获取此RDD检查点的文件的名称
   */
  def getCheckpointFile(): Optional[String] = {
    JavaUtils.optionToOptional(rdd.getCheckpointFile)
  }

  /** A description of this RDD and its recursive dependencies for debugging.
    * 此RDD的描述及其调试的递归依赖性*/
  def toDebugString(): String = {
    rdd.toDebugString
  }

  /**
   * Returns the top k (largest) elements from this RDD as defined by
   * the specified Comparator[T].
   * @param num k, the number of top elements to return
   * @param comp the comparator that defines the order
   * @return an array of top elements
   */
  def top(num: Int, comp: Comparator[T]): JList[T] = {
    import scala.collection.JavaConversions._
    val topElems = rdd.top(num)(Ordering.comparatorToOrdering(comp))
    val arr: java.util.Collection[T] = topElems.toSeq
    new java.util.ArrayList(arr)
  }

  /**
   * Returns the top k (largest) elements from this RDD using the
   * natural ordering for T.
    * 使用T的自然顺序返回此RDD中的前k个(最大)元素。
   * @param num k, the number of top elements to return
   * @return an array of top elements
   */
  def top(num: Int): JList[T] = {
    val comp = com.google.common.collect.Ordering.natural().asInstanceOf[Comparator[T]]
    top(num, comp)
  }

  /**
   * Returns the first k (smallest) elements from this RDD as defined by
   * the specified Comparator[T] and maintains the order.
   * @param num k, the number of elements to return
   * @param comp the comparator that defines the order
   * @return an array of top elements
   */
  def takeOrdered(num: Int, comp: Comparator[T]): JList[T] = {
    import scala.collection.JavaConversions._
    val topElems = rdd.takeOrdered(num)(Ordering.comparatorToOrdering(comp))
    val arr: java.util.Collection[T] = topElems.toSeq
    new java.util.ArrayList(arr)
  }

  /**
   * Returns the maximum element from this RDD as defined by the specified
   * Comparator[T].
    * 返回指定Comparator [T]定义的RDD中的最大元素
   * @param comp the comparator that defines ordering
   * @return the maximum of the RDD
   * */
  def max(comp: Comparator[T]): T = {
    rdd.max()(Ordering.comparatorToOrdering(comp))
  }

  /**
   * Returns the minimum element from this RDD as defined by the specified
   * Comparator[T].
    * 返回指定Comparator [T]定义的RDD中的最小元素
   * @param comp the comparator that defines ordering
   * @return the minimum of the RDD
   * */
  def min(comp: Comparator[T]): T = {
    rdd.min()(Ordering.comparatorToOrdering(comp))
  }

  /**
   * Returns the first k (smallest) elements from this RDD using the
   * natural ordering for T while maintain the order.
   * @param num k, the number of top elements to return
   * @return an array of top elements
   */
  def takeOrdered(num: Int): JList[T] = {
    val comp = com.google.common.collect.Ordering.natural().asInstanceOf[Comparator[T]]
    takeOrdered(num, comp)
  }

  /**
   * Return approximate number of distinct elements in the RDD.
   * 返回RDD中不同元素的近似数量
   * The algorithm used is based on streamlib's implementation of "HyperLogLog in Practice:
   * Algorithmic Engineering of a State of The Art Cardinality Estimation Algorithm", available
   * <a href="http://dx.doi.org/10.1145/2452376.2452456">here</a>.
   *
   * @param relativeSD Relative accuracy. Smaller values create counters that require more space.
   *                   It must be greater than 0.000017.
   */
  def countApproxDistinct(relativeSD: Double): Long = rdd.countApproxDistinct(relativeSD)

  def name(): String = rdd.name

  /**
   * The asynchronous version of `count`, which returns a
   * future for counting the number of elements in this RDD.
    * `count`的异步版本,它返回一个异步版本`count`,它返回一个
   */
  def countAsync(): JavaFutureAction[JLong] = {
    new JavaFutureActionWrapper[Long, JLong](rdd.countAsync(), JLong.valueOf)
  }

  /**
   * The asynchronous version of `collect`, which returns a future for
   * retrieving an array containing all of the elements in this RDD.
   */
  def collectAsync(): JavaFutureAction[JList[T]] = {
    new JavaFutureActionWrapper(rdd.collectAsync(), (x: Seq[T]) => x.asJava)
  }

  /**
   * The asynchronous version of the `take` action, which returns a
   * future for retrieving the first `num` elements of this RDD.
   */
  def takeAsync(num: Int): JavaFutureAction[JList[T]] = {
    new JavaFutureActionWrapper(rdd.takeAsync(num), (x: Seq[T]) => x.asJava)
  }

  /**
   * The asynchronous version of the `foreach` action, which
   * applies a function f to all the elements of this RDD.
   */
  def foreachAsync(f: VoidFunction[T]): JavaFutureAction[Void] = {
    new JavaFutureActionWrapper[Unit, Void](rdd.foreachAsync(x => f.call(x)),
      { x => null.asInstanceOf[Void] })
  }

  /**
   * The asynchronous version of the `foreachPartition` action, which
   * applies a function f to each partition of this RDD.
   */
  def foreachPartitionAsync(f: VoidFunction[java.util.Iterator[T]]): JavaFutureAction[Void] = {
    new JavaFutureActionWrapper[Unit, Void](rdd.foreachPartitionAsync(x => f.call(x)),
      { x => null.asInstanceOf[Void] })
  }
}
