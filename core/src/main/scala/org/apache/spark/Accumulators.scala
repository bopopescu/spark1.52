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

import java.io.{ObjectInputStream, Serializable}

import scala.collection.generic.Growable
import scala.collection.Map
import scala.collection.mutable
import scala.ref.WeakReference
import scala.reflect.ClassTag

import org.apache.spark.serializer.JavaSerializer
import org.apache.spark.util.Utils

/**
 * A data type that can be accumulated, ie has an commutative and associative "add" operation,
 * but where the result type, `R`, may be different from the element type being added, `T`.
 * 可以累加数据类型,有一个可交换的和关联“添加”操作结果类型,可能与添加的元素类型不同.
 *
 * You must define how to add data, and how to merge two of these together.  For some data types,
 * such as a counter, these might be the same operation. In that case, you can use the simpler
 * [[org.apache.spark.Accumulator]]. They won't always be the same, though -- e.g., imagine you are
 * accumulating a set. You will add items to the set, and you will union two sets together.
  *
  *必须定义如何添加数据,以及如何将其中的两个合并在一起,对于一些数据类型,如计数器,这些可能是相同的操作,
  * 在这种情况下,您可以使用更简单的[[org.apache.spark.Accumulator]],尽管如此,他们并不总是相同的,
  * 例如,想象你正在积累一套,您将添加项目到集合,将联合两个集合在一起
 *
 * @param initialValue initial value of accumulator 累加器的初始值
 * @param param helper object defining how to add elements of type `R` and `T` 帮助对象定义如何添加类型“R”和“T”的元素
 * @param name human-readable name for use in Spark's web UI 在Spark的Web UI中使用的可读名称
 * @param internal if this [[Accumulable]] is internal. Internal [[Accumulable]]s will be reported
 *                 to the driver via heartbeats. For internal [[Accumulable]]s, `R` must be
 *                 thread safe so that they can be reported correctly.
 * @tparam R the full accumulated data (result type) 全部累积数据(结果类型)
 * @tparam T partial data that can be added in 可以添加的部分数据
 */
class Accumulable[R, T] private[spark] (
    @transient initialValue: R,
    param: AccumulableParam[R, T],
    val name: Option[String],
    internal: Boolean)
  extends Serializable {

  private[spark] def this(
      @transient initialValue: R, param: AccumulableParam[R, T], internal: Boolean) = {
    this(initialValue, param, None, internal)
  }

  def this(@transient initialValue: R, param: AccumulableParam[R, T], name: Option[String]) =
    this(initialValue, param, name, false)

  def this(@transient initialValue: R, param: AccumulableParam[R, T]) =
    this(initialValue, param, None)

  val id: Long = Accumulators.newId

  @volatile @transient private var value_ : R = initialValue // Current value on master
  val zero = param.zero(initialValue)  // Zero value to be passed to workers
  private var deserialized = false

  Accumulators.register(this)

  /**
   * If this [[Accumulable]] is internal. Internal [[Accumulable]]s will be reported to the driver
   * via heartbeats. For internal [[Accumulable]]s, `R` must be thread safe so that they can be
   * reported correctly.
   */
  private[spark] def isInternal: Boolean = internal

  /**
   * Add more data to this accumulator / accumulable
    * 将更多数据添加到此累加器/可累加
   * @param term the data to add
   */
  def += (term: T) { value_ = param.addAccumulator(value_, term) }

  /**
   * Add more data to this accumulator / accumulable
    * 将更多数据添加到此累加器/可累加
   * @param term the data to add
   */
  def add(term: T) { value_ = param.addAccumulator(value_, term) }

  /**
   * Merge two accumulable objects together
    * 将两个可累积的对象合并在一起
   *
   * Normally, a user will not want to use this version, but will instead call `+=`.
    * 通常，用户不想使用此版本，而是调用`+ =`。
   * @param term the other `R` that will get merged with this
   */
  def ++= (term: R) { value_ = param.addInPlace(value_, term)}

  /**
   * Merge two accumulable objects together
   * 将两个可累积的对象合并在一起
   * Normally, a user will not want to use this version, but will instead call `add`.
    * 通常，用户不想使用此版本，而是调用`add`。
   * @param term the other `R` that will get merged with this
   */
  def merge(term: R) { value_ = param.addInPlace(value_, term)}

  /**
   * Access the accumulator's current value; only allowed on master.
    * 访问累加器的当前值; 只允许在master。
   */
  def value: R = {
    if (!deserialized) {
      value_
    } else {
      throw new UnsupportedOperationException("Can't read accumulator value in task")
    }
  }

  /**
   * Get the current value of this accumulator from within a task.
   *从任务中获取此累加器的当前值。
   * This is NOT the global value of the accumulator.  To get the global value after a
   * completed operation on the dataset, call `value`.
   * 这不是累加器的全局值。 要在数据集完成操作后获取全局值，请调用`value`。
   * The typical use of this method is to directly mutate the local value, eg., to add
   * an element to a Set.
    * 该方法的典型用途是直接突变本地值，例如，向元素添加元素。
   */
  def localValue: R = value_

  /**
   * Set the accumulator's value; only allowed on master.
    * 设置累加器的值; 只允许在master。
   */
  def value_= (newValue: R) {
    if (!deserialized) {
      value_ = newValue
    } else {
      throw new UnsupportedOperationException("Can't assign accumulator value in task")
    }
  }

  /**
   * Set the accumulator's value; only allowed on master
    * 设置累加器的值; 只允许在master
   */
  def setValue(newValue: R) {
    this.value = newValue
  }

  // Called by Java when deserializing an object
  //反序列化对象时由Java调用
  private def readObject(in: ObjectInputStream): Unit = Utils.tryOrIOException {
    in.defaultReadObject()
    value_ = zero
    deserialized = true
    // Automatically register the accumulator when it is deserialized with the task closure.
    //当任务关闭反序列化时，自动对累加器进行注册。
    // Note internal accumulators sent with task are deserialized before the TaskContext is created
    // and are registered in the TaskContext constructor. Other internal accumulators, such SQL
    // metrics, still need to register here.
    //在TaskContext创建之前,注意与任务一起发送的内部累加器是反序列化的,并在TaskContext构造函数中注册,
    // 其他内部累加器,这样的SQL指标,仍然需要在这里注册。
    val taskContext = TaskContext.get()
    if (taskContext != null) {
      taskContext.registerAccumulator(this)
    }
  }

  override def toString: String = if (value_ == null) "null" else value_.toString
}

/**
 * Helper object defining how to accumulate values of a particular type. An implicit
 * AccumulableParam needs to be available when you create [[Accumulable]]s of a specific type.
  * 定义如何积累特定类型的值的帮助对象,当您创建特定类型的[可累积时，隐含的AccumulableParam需要可用。
 *
 * @tparam R the full accumulated data (result type)
 * @tparam T partial data that can be added in
 */
trait AccumulableParam[R, T] extends Serializable {
  /**
   * Add additional data to the accumulator value. Is allowed to modify and return `r`
   * for efficiency (to avoid allocating objects).
   * 向累加器添加数据,允许修改和返回R的效率(以避免分配对象)
   * @param r the current value of the accumulator 累加器的当前值
   * @param t the data to be added to the accumulator 向累加器添加数据
   * @return the new value of the accumulator  返回累加器新值
   */
  def addAccumulator(r: R, t: T): R

  /**
   * Merge two accumulated values together. Is allowed to modify and return the first value
   * for efficiency (to avoid allocating objects).
   * 将两个累计值合并在一起,允许修改和回报效率第一值（避免配置对象）
   *
   * @param r1 one set of accumulated data
   * @param r2 another set of accumulated data
   * @return both data sets merged together
   */
  def addInPlace(r1: R, r2: R): R

  /**
   * Return the "zero" (identity) value for an accumulator type, given its initial value. For
   * 返回一个累加器类型的"零"(标识)值,给定它的初始值
   * example, if R was a vector of N dimensions, this would return a vector of N zeroes.
   * 如果R是一个N维向量,这将返回一个N个零向量
   */
  def zero(initialValue: R): R
}

private[spark] class
GrowableAccumulableParam[R <% Growable[T] with TraversableOnce[T] with Serializable: ClassTag, T]
  extends AccumulableParam[R, T] {

  def addAccumulator(growable: R, elem: T): R = {
    growable += elem
    growable
  }

  def addInPlace(t1: R, t2: R): R = {
    t1 ++= t2
    t1
  }

  def zero(initialValue: R): R = {
    // We need to clone initialValue, but it's hard to specify that R should also be Cloneable.
    // Instead we'll serialize it to a buffer and load it back.
    //我们需要克隆initialValue,但很难指定R也应该是可克隆的。相反,我们将其序列化到缓冲区并加载它。
    val ser = new JavaSerializer(new SparkConf(false)).newInstance()
    val copy = ser.deserialize[R](ser.serialize(initialValue))
    ///如果它包含东西
    copy.clear()   // In case it contained stuff
    copy
  }
}

/**
 * A simpler value of [[Accumulable]] where the result type being accumulated is the same
 * as the types of elements being merged, i.e. variables that are only "added" to through an
 * associative operation and can therefore be efficiently supported in parallel. They can be used
 * to implement counters (as in MapReduce) or sums. Spark natively supports accumulators of numeric
 * value types, and programmers can add support for new types.
  * 累积结果类型的[累计的一个较简单的值是相同的作为要合并的元素的类型，即仅通过“添加”的变量相关操作，因此可以并行有效地支持。
  * 它们可用于实现计数器（如MapReduce）或总和。 Spark本身支持数值类型的累加器，程序员可以添加对新类型的支持。
 *
 * An accumulator is created from an initial value `v` by calling [[SparkContext#accumulator]].
 * Tasks running on the cluster can then add to it using the [[Accumulable#+=]] operator.
 * However, they cannot read its value. Only the driver program can read the accumulator's value,
 * using its value method.
 *
 * The interpreter session below shows an accumulator being used to add up the elements of an array:
  * 下面的解释器会话显示一个累加器用于将数组的元素相加：
 *
 * {{{
 * scala> val accum = sc.accumulator(0)
 * accum: spark.Accumulator[Int] = 0
 *
 * scala> sc.parallelize(Array(1, 2, 3, 4)).foreach(x => accum += x)
 * ...
 * 10/09/29 18:41:08 INFO SparkContext: Tasks finished in 0.317106 s
 *
 * scala> accum.value
 * res2: Int = 10
 * }}}
 *
 * @param initialValue initial value of accumulator
 * @param param helper object defining how to add elements of type `T`
 * @tparam T result type
 */
class Accumulator[T] private[spark] (
    @transient private[spark] val initialValue: T,
    param: AccumulatorParam[T],
    name: Option[String],
    internal: Boolean)
  extends Accumulable[T, T](initialValue, param, name, internal) {

  def this(initialValue: T, param: AccumulatorParam[T], name: Option[String]) = {
    this(initialValue, param, name, false)
  }

  def this(initialValue: T, param: AccumulatorParam[T]) = {
    this(initialValue, param, None, false)
  }
}

/**
 * A simpler version of [[org.apache.spark.AccumulableParam]] where the only data type you can add
 * in is the same type as the accumulated value. An implicit AccumulatorParam object needs to be
 * available when you create Accumulators of a specific type.
  * 您可以添加的唯一数据类型的[[org.apache.spark.AccumulableParam]]的更简单版本与累积值相同,
  * 在创建特定类型的累加器时,需要使用隐式的AccumulatorParam对象。
 *
 * @tparam T type of value to accumulate
 */
trait AccumulatorParam[T] extends AccumulableParam[T, T] {
  def addAccumulator(t1: T, t2: T): T = {
    addInPlace(t1, t2)
  }
}

object AccumulatorParam {

  // The following implicit objects were in SparkContext before 1.2 and users had to
  // `import SparkContext._` to enable them. Now we move them here to make the compiler find
  // them automatically. However, as there are duplicate codes in SparkContext for backward
  // compatibility, please update them accordingly if you modify the following implicit objects.
  //下隐含对象在1.2之前的SparkContext中，用户必须使用`import SparkContext._`来启用它们。
  //现在我们将它们移到这里,使编译器自动找到它们。 但是,由于向后兼容的SparkContext中有重复的代码,因此如果修改以下隐含对象,请相应更新它们。

  implicit object DoubleAccumulatorParam extends AccumulatorParam[Double] {
    def addInPlace(t1: Double, t2: Double): Double = t1 + t2
    def zero(initialValue: Double): Double = 0.0
  }

  implicit object IntAccumulatorParam extends AccumulatorParam[Int] {
    def addInPlace(t1: Int, t2: Int): Int = t1 + t2
    def zero(initialValue: Int): Int = 0
  }

  implicit object LongAccumulatorParam extends AccumulatorParam[Long] {
    def addInPlace(t1: Long, t2: Long): Long = t1 + t2
    def zero(initialValue: Long): Long = 0L
  }

  implicit object FloatAccumulatorParam extends AccumulatorParam[Float] {
    def addInPlace(t1: Float, t2: Float): Float = t1 + t2
    def zero(initialValue: Float): Float = 0f
  }

  // TODO: Add AccumulatorParams for other types, e.g. lists and strings
}

// TODO: The multi-thread support in accumulators is kind of lame; check
// if there's a more intuitive way of doing it right
//如果有一种更直观的方式做正确的事情
private[spark] object Accumulators extends Logging {
  /**
   * This global map holds the original accumulator objects that are created on the driver.
   * It keeps weak references to these objects so that accumulators can be garbage-collected
   * once the RDDs and user-code that reference them are cleaned up.
    * 此全局映射保存在驱动程序上创建的原始累加器对象,它对这些对象保持弱引用,
    * 以便在清除引用它们的RDD和用户代码之后,可以对垃圾收集器进行垃圾收集。
   */
  val originals = mutable.Map[Long, WeakReference[Accumulable[_, _]]]()

  private var lastId: Long = 0

  def newId(): Long = synchronized {
    lastId += 1
    lastId
  }

  def register(a: Accumulable[_, _]): Unit = synchronized {
    originals(a.id) = new WeakReference[Accumulable[_, _]](a)
  }

  def remove(accId: Long) {
    synchronized {
      originals.remove(accId)
    }
  }

  // Add values to the original accumulators with some given IDs
  //将值添加到具有某些给定ID的原始累加器
  def add(values: Map[Long, Any]): Unit = synchronized {
    for ((id, value) <- values) {
      if (originals.contains(id)) {
        // Since we are now storing weak references, we must check whether the underlying data
        // is valid.
        //由于我们现在存储弱引用,所以我们必须检查底层数据是否有效。
        originals(id).get match {
          case Some(accum) => accum.asInstanceOf[Accumulable[Any, Any]] ++= value
          case None =>
            throw new IllegalAccessError("Attempted to access garbage collected Accumulator.")
        }
      } else {
        logWarning(s"Ignoring accumulator update for unknown accumulator id $id")
      }
    }
  }

}

private[spark] object InternalAccumulator {
  val PEAK_EXECUTION_MEMORY = "peakExecutionMemory"
  val TEST_ACCUMULATOR = "testAccumulator"

  // For testing only.仅用于测试
  // This needs to be a def since we don't want to reuse the same accumulator across stages.
  //这需要是一个def,因为我们不想跨阶段重用同一个累加器。
  private def maybeTestAccumulator: Option[Accumulator[Long]] = {
    if (sys.props.contains("spark.testing")) {
      Some(new Accumulator(
        0L, AccumulatorParam.LongAccumulatorParam, Some(TEST_ACCUMULATOR), internal = true))
    } else {
      None
    }
  }

  /**
   * Accumulators for tracking internal metrics.
   * 追踪内部指标的累加器
   * These accumulators are created with the stage such that all tasks in the stage will
   * add to the same set of accumulators. We do this to report the distribution of accumulator
   * values across all tasks within each stage.
    * 这些累加器是通过stage创建的,使得stage中的所有任务将添加到同一组累加器,
    * 我们这样做来报告累加器值在每个阶段内的所有任务的分配。
   */
  def create(sc: SparkContext): Seq[Accumulator[Long]] = {
    val internalAccumulators = Seq(
        // Execution memory refers to the memory used by internal data structures created
        // during shuffles, aggregations and joins. The value of this accumulator should be
        // approximately the sum of the peak sizes across all such data structures created
        // in this task. For SQL jobs, this only tracks all unsafe operators and ExternalSort.
         //执行存储器是指在混洗，聚合和连接期间创建的内部数据结构所使用的内存。
        // 此累加器的值应大约是在此任务中创建的所有此类数据结构之间的峰值大小的总和。
        // 对于SQL作业，这仅跟踪所有不安全的操作符和ExternalSort。
         new Accumulator(
          0L, AccumulatorParam.LongAccumulatorParam, Some(PEAK_EXECUTION_MEMORY), internal = true)
      ) ++ maybeTestAccumulator.toSeq
    internalAccumulators.foreach { accumulator =>
      sc.cleaner.foreach(_.registerAccumulatorForCleanup(accumulator))
    }
    internalAccumulators
  }
}
