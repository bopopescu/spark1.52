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

package org.apache.spark.sql.catalyst.expressions

import java.util.UUID

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.types._

object NamedExpression {
  private val curId = new java.util.concurrent.atomic.AtomicLong()
  private[expressions] val jvmId = UUID.randomUUID()
  def newExprId: ExprId = ExprId(curId.getAndIncrement(), jvmId)
  def unapply(expr: NamedExpression): Option[(String, DataType)] = Some(expr.name, expr.dataType)
}

/**
 * A globally unique id for a given named expression.
  * 给定命名表达式的全局唯一ID
 * Used to identify which attribute output by a relation is being
 * referenced in a subsequent computation.
  * 用于标识在后续计算中引用关系输出的属性
 *
 * The `id` field is unique within a given JVM, while the `uuid` is used to uniquely identify JVMs.
  * `id`字段在给定的JVM中是唯一的,而`uuid`用于唯一地标识JVM
 */
case class ExprId(id: Long, jvmId: UUID)

object ExprId {
  def apply(id: Long): ExprId = ExprId(id, NamedExpression.jvmId)
}

/**
 * An [[Expression]] that is named.
 */
trait NamedExpression extends Expression {

  /** We should never fold named expressions in order to not remove the alias.
    * 我们不应该折叠命名表达式以便不删除别名*/
  override def foldable: Boolean = false

  def name: String
  def exprId: ExprId

  /**
   * Returns a dot separated fully qualified name for this attribute.  Given that there can be
   * multiple qualifiers, it is possible that there are other possible way to refer to this
   * attribute.
    * 返回此属性的点分隔的完全限定名称,鉴于可以有多个限定符,可能还有其他可能的方法来引用此属性
   */
  def qualifiedName: String = (qualifiers.headOption.toSeq :+ name).mkString(".")

  /**
   * All possible qualifiers for the expression.
    * 表达式的所有可能的限定符
   *
   * For now, since we do not allow using original table name to qualify a column name once the
   * table is aliased, this can only be:
    * 目前,由于我们不允许在表格别名后使用原始表名来限定列名,因此这只能是：
   *
   * 1. Empty Seq: when an attribute doesn't have a qualifier,Empty Seq：当属性没有限定符时，
   *    e.g. top level attributes aliased in the SELECT clause, or column from a LocalRelation.
   * 2. Single element: either the table name or the alias name of the table.
    *   单个元素：表名或表的别名。
   */
  def qualifiers: Seq[String]

  def toAttribute: Attribute

  /** Returns the metadata when an expression is a reference to another expression with metadata.
    * 当表达式是对具有元数据的另一个表达式的引用时,返回元数据*/
  def metadata: Metadata = Metadata.empty

  protected def typeSuffix =
    if (resolved) {
      dataType match {
        case LongType => "L"
        case _ => ""
      }
    } else {
      ""
    }
}

abstract class Attribute extends LeafExpression with NamedExpression {

  override def references: AttributeSet = AttributeSet(this)

  def withNullability(newNullability: Boolean): Attribute
  def withQualifiers(newQualifiers: Seq[String]): Attribute
  def withName(newName: String): Attribute

  override def toAttribute: Attribute = this
  def newInstance(): Attribute

}

/**
 * Used to assign a new name to a computation.用于为计算分配新名称
 * For example the SQL expression "1 + 1 AS a" could be represented as follows:
  * 例如,SQL表达式“1 + 1 AS a”可以表示如下:
 *  Alias(Add(Literal(1), Literal(1)), "a")()
 *
 * Note that exprId and qualifiers are in a separate parameter list because
 * we only pattern match on child and name.
  *
  * 请注意,exprId和限定符位于单独的参数列表中,因为我们只对child和name进行模式匹配。
 *
 * @param child the computation being performed 正在执行的计算
 * @param name the name to be associated with the result of computing [[child]].
  *             与计算[[child]]结果相关联的名称
 * @param exprId A globally unique id used to check if an [[AttributeReference]] refers to this
 *               alias. Auto-assigned if left blank.
 * @param explicitMetadata Explicit metadata associated with this alias that overwrites child's.
 */
case class Alias(child: Expression, name: String)(
    val exprId: ExprId = NamedExpression.newExprId,
    val qualifiers: Seq[String] = Nil,
    val explicitMetadata: Option[Metadata] = None)
  extends UnaryExpression with NamedExpression {

  // Alias(Generator, xx) need to be transformed into Generate(generator, ...)
  override lazy val resolved =
    childrenResolved && checkInputDataTypes().isSuccess && !child.isInstanceOf[Generator]

  override def eval(input: InternalRow): Any = child.eval(input)

  /** Just a simple passthrough for code generation.
    * 只是一个代码生成的简单传递*/
  override def gen(ctx: CodeGenContext): GeneratedExpressionCode = child.gen(ctx)
  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = ""

  override def dataType: DataType = child.dataType
  override def nullable: Boolean = child.nullable
  override def metadata: Metadata = {
    explicitMetadata.getOrElse {
      child match {
        case named: NamedExpression => named.metadata
        case _ => Metadata.empty
      }
    }
  }

  override def toAttribute: Attribute = {
    if (resolved) {
      AttributeReference(name, child.dataType, child.nullable, metadata)(exprId, qualifiers)
    } else {
      UnresolvedAttribute(name)
    }
  }

  override def toString: String = s"$child AS $name#${exprId.id}$typeSuffix"

  override protected final def otherCopyArgs: Seq[AnyRef] = {
    exprId :: qualifiers :: explicitMetadata :: Nil
  }

  override def equals(other: Any): Boolean = other match {
    case a: Alias =>
      name == a.name && exprId == a.exprId && child == a.child && qualifiers == a.qualifiers &&
        explicitMetadata == a.explicitMetadata
    case _ => false
  }
}

/**
 * A reference to an attribute produced by another operator in the tree.
  * 对树中另一个运算符生成的属性的引用
 *
 * @param name The name of this attribute, should only be used during analysis or for debugging.
  *             此属性的名称应仅在分析期间或用于调试时使用
 * @param dataType The [[DataType]] of this attribute.
 * @param nullable True if null is a valid value for this attribute.
 * @param metadata The metadata of this attribute.
 * @param exprId A globally unique id used to check if different AttributeReferences refer to the
 *               same attribute.
 * @param qualifiers a list of strings that can be used to referred to this attribute in a fully
 *                   qualified way. Consider the examples tableName.name, subQueryAlias.name.
 *                   tableName and subQueryAlias are possible qualifiers.
 */
case class AttributeReference(
    name: String,
    dataType: DataType,
    nullable: Boolean = true,
    override val metadata: Metadata = Metadata.empty)(
    val exprId: ExprId = NamedExpression.newExprId,
    val qualifiers: Seq[String] = Nil)
  extends Attribute with Unevaluable {

  /**
   * Returns true iff the expression id is the same for both attributes.
    * 如果两个属性的表达式id相同,则返回true
   */
  def sameRef(other: AttributeReference): Boolean = this.exprId == other.exprId

  override def equals(other: Any): Boolean = other match {
    case ar: AttributeReference => name == ar.name && exprId == ar.exprId && dataType == ar.dataType
    case _ => false
  }

  override def semanticEquals(other: Expression): Boolean = other match {
    case ar: AttributeReference => sameRef(ar)
    case _ => false
  }

  override def hashCode: Int = {
    // See http://stackoverflow.com/questions/113511/hash-code-implementation
    var h = 17
    h = h * 37 + exprId.hashCode()
    h = h * 37 + dataType.hashCode()
    h = h * 37 + metadata.hashCode()
    h
  }

  override def newInstance(): AttributeReference =
    AttributeReference(name, dataType, nullable, metadata)(qualifiers = qualifiers)

  /**
   * Returns a copy of this [[AttributeReference]] with changed nullability.
   */
  override def withNullability(newNullability: Boolean): AttributeReference = {
    if (nullable == newNullability) {
      this
    } else {
      AttributeReference(name, dataType, newNullability, metadata)(exprId, qualifiers)
    }
  }

  override def withName(newName: String): AttributeReference = {
    if (name == newName) {
      this
    } else {
      AttributeReference(newName, dataType, nullable)(exprId, qualifiers)
    }
  }

  /**
   * Returns a copy of this [[AttributeReference]] with new qualifiers.
    * 使用新限定符返回此[[AttributeReference]]的副本
   */
  override def withQualifiers(newQualifiers: Seq[String]): AttributeReference = {
    if (newQualifiers.toSet == qualifiers.toSet) {
      this
    } else {
      AttributeReference(name, dataType, nullable, metadata)(exprId, newQualifiers)
    }
  }

  def withExprId(newExprId: ExprId): AttributeReference = {
    if (exprId == newExprId) {
      this
    } else {
      AttributeReference(name, dataType, nullable, metadata)(newExprId, qualifiers)
    }
  }

  override def toString: String = s"$name#${exprId.id}$typeSuffix"
}

/**
 * A place holder used when printing expressions without debugging information such as the
 * expression id or the unresolved indicator.
  * 打印表达式时使用的占位符,无需调试表达式ID或未解析的指示符等信息
 */
case class PrettyAttribute(name: String) extends Attribute with Unevaluable {

  override def toString: String = name

  override def withNullability(newNullability: Boolean): Attribute =
    throw new UnsupportedOperationException
  override def newInstance(): Attribute = throw new UnsupportedOperationException
  override def withQualifiers(newQualifiers: Seq[String]): Attribute =
    throw new UnsupportedOperationException
  override def withName(newName: String): Attribute = throw new UnsupportedOperationException
  override def qualifiers: Seq[String] = throw new UnsupportedOperationException
  override def exprId: ExprId = throw new UnsupportedOperationException
  override def nullable: Boolean = throw new UnsupportedOperationException
  override def dataType: DataType = NullType
}

object VirtualColumn {
  val groupingIdName: String = "grouping__id"
  val groupingIdAttribute: UnresolvedAttribute = UnresolvedAttribute(groupingIdName)
}
