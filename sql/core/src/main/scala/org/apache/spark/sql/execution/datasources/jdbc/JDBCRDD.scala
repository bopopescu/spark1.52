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

package org.apache.spark.sql.execution.datasources.jdbc

import java.sql.{Connection, Date, DriverManager, ResultSet, ResultSetMetaData, SQLException, Timestamp}
import java.util.Properties

import org.apache.commons.lang3.StringUtils

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.SpecificMutableRow
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.jdbc.JdbcDialects
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.{Logging, Partition, SparkContext, TaskContext}

/**
 * Data corresponding to one partition of a JDBCRDD.
  * 对应于JDBCRDD的一个分区的数据
 */
private[sql] case class JDBCPartition(whereClause: String, idx: Int) extends Partition {
  override def index: Int = idx
}


private[sql] object JDBCRDD extends Logging {

  /**
   * Maps a JDBC type to a Catalyst type.  This function is called only when
   * the JdbcDialect class corresponding to your database driver returns null.
    *
    * 将JDBC类型映射到Catalyst类型,仅当与数据库驱动程序对应的JdbcDialect类返回null时,才会调用此函数。
   *
   * @param sqlType - A field of java.sql.Types
   * @return The Catalyst type corresponding to sqlType.
   */
  private def getCatalystType(
      sqlType: Int,
      precision: Int,
      scale: Int,
      signed: Boolean): DataType = {
    val answer = sqlType match {
      // scalastyle:off
      case java.sql.Types.ARRAY         => null
      case java.sql.Types.BIGINT        => if (signed) { LongType } else { DecimalType(20,0) }
      case java.sql.Types.BINARY        => BinaryType
      case java.sql.Types.BIT           => BooleanType // @see JdbcDialect for quirks
      case java.sql.Types.BLOB          => BinaryType
      case java.sql.Types.BOOLEAN       => BooleanType
      case java.sql.Types.CHAR          => StringType
      case java.sql.Types.CLOB          => StringType
      case java.sql.Types.DATALINK      => null
      case java.sql.Types.DATE          => DateType
      case java.sql.Types.DECIMAL
        if precision != 0 || scale != 0 => DecimalType.bounded(precision, scale)
      case java.sql.Types.DECIMAL       => DecimalType.SYSTEM_DEFAULT
      case java.sql.Types.DISTINCT      => null
      case java.sql.Types.DOUBLE        => DoubleType
      case java.sql.Types.FLOAT         => FloatType
      case java.sql.Types.INTEGER       => if (signed) { IntegerType } else { LongType }
      case java.sql.Types.JAVA_OBJECT   => null
      case java.sql.Types.LONGNVARCHAR  => StringType
      case java.sql.Types.LONGVARBINARY => BinaryType
      case java.sql.Types.LONGVARCHAR   => StringType
      case java.sql.Types.NCHAR         => StringType
      case java.sql.Types.NCLOB         => StringType
      case java.sql.Types.NULL          => null
      case java.sql.Types.NUMERIC
        if precision != 0 || scale != 0 => DecimalType.bounded(precision, scale)
      case java.sql.Types.NUMERIC       => DecimalType.SYSTEM_DEFAULT
      case java.sql.Types.NVARCHAR      => StringType
      case java.sql.Types.OTHER         => null
      case java.sql.Types.REAL          => DoubleType
      case java.sql.Types.REF           => StringType
      case java.sql.Types.ROWID         => LongType
      case java.sql.Types.SMALLINT      => IntegerType
      case java.sql.Types.SQLXML        => StringType
      case java.sql.Types.STRUCT        => StringType
      case java.sql.Types.TIME          => TimestampType
      case java.sql.Types.TIMESTAMP     => TimestampType
      case java.sql.Types.TINYINT       => IntegerType
      case java.sql.Types.VARBINARY     => BinaryType
      case java.sql.Types.VARCHAR       => StringType
      case _                            => null
      // scalastyle:on
    }

    if (answer == null) throw new SQLException("Unsupported type " + sqlType)
    answer
  }

  /**
   * Takes a (schema, table) specification and returns the table's Catalyst
   * schema.
    * 采用(模式,表)规范并返回表的Catalyst模式
   *
   * @param url - The JDBC url to fetch information from.用于从中获取信息的JDBC URL
   * @param table - The table name of the desired table.  This may also be a
   *   SQL query wrapped in parentheses.
    *   所需表的表名,这也可能是括在括号中的SQL查询
   *
   * @return A StructType giving the table's Catalyst schema.
    *         给出表的Catalyst模式的StructType
   * @throws SQLException if the table specification is garbage.
   * @throws SQLException if the table contains an unsupported type.
   */
  def resolveTable(url: String, table: String, properties: Properties): StructType = {
    val dialect = JdbcDialects.get(url)
    val conn: Connection = getConnector(properties.getProperty("driver"), url, properties)()
    try {
      val rs = conn.prepareStatement(s"SELECT * FROM $table WHERE 1=0").executeQuery()
      try {
        val rsmd = rs.getMetaData
        val ncols = rsmd.getColumnCount
        val fields = new Array[StructField](ncols)
        var i = 0
        while (i < ncols) {
          val columnName = rsmd.getColumnLabel(i + 1)
          val dataType = rsmd.getColumnType(i + 1)
          val typeName = rsmd.getColumnTypeName(i + 1)
          val fieldSize = rsmd.getPrecision(i + 1)
          val fieldScale = rsmd.getScale(i + 1)
          val isSigned = rsmd.isSigned(i + 1)
          val nullable = rsmd.isNullable(i + 1) != ResultSetMetaData.columnNoNulls
          val metadata = new MetadataBuilder().putString("name", columnName)
          val columnType =
            dialect.getCatalystType(dataType, typeName, fieldSize, metadata).getOrElse(
              getCatalystType(dataType, fieldSize, fieldScale, isSigned))
          fields(i) = StructField(columnName, columnType, nullable, metadata.build())
          i = i + 1
        }
        return new StructType(fields)
      } finally {
        rs.close()
      }
    } finally {
      conn.close()
    }

    throw new RuntimeException("This line is unreachable.")
  }

  /**
   * Prune all but the specified columns from the specified Catalyst schema.
    * 修剪除指定的Catalyst模式中的指定列以外的所有列
   *
   * @param schema - The Catalyst schema of the master table
    *               主表的Catalyst模式
   * @param columns - The list of desired columns 所需列的列表
   *
   * @return A Catalyst schema corresponding to columns in the given order.
   */
  private def pruneSchema(schema: StructType, columns: Array[String]): StructType = {
    val fieldMap = Map(schema.fields map { x => x.metadata.getString("name") -> x }: _*)
    new StructType(columns map { name => fieldMap(name) })
  }

  /**
   * Given a driver string and an url, return a function that loads the
   * specified driver string then returns a connection to the JDBC url.
    * 给定一个驱动程序字符串和一个url,返回一个加载指定驱动程序字符串的函数,然后返回与JDBC url的连接
   * getConnector is run on the driver code, while the function it returns
   * is run on the executor.
   * getConnector在驱动程序代码上运行,而它返回的函数在执行程序上运行
   * @param driver - The class name of the JDBC driver for the given url, or null if the class name
   *                 is not necessary.
    *                 给定URL的JDBC驱动程序的类名,如果不需要类名,则为null
   * @param url - The JDBC url to connect to.连接的JDBC URL
   *
   * @return A function that loads the driver and connects to the url.
    *         一个加载驱动程序并连接到url的函数
   */
  def getConnector(driver: String, url: String, properties: Properties): () => Connection = {
    () => {
      try {
        if (driver != null) DriverRegistry.register(driver)
      } catch {
        case e: ClassNotFoundException =>
          logWarning(s"Couldn't find class $driver", e)
      }
      DriverManager.getConnection(url, properties)
    }
  }

  /**
   * Build and return JDBCRDD from the given information.
    * 从给定信息构建并返回JDBCRDD
   *
   * @param sc - Your SparkContext.
   * @param schema - The Catalyst schema of the underlying database table.
    *               底层数据库表的Catalyst模式
   * @param driver - The class name of the JDBC driver for the given url.
    *               给定URL的JDBC驱动程序的类名
   * @param url - The JDBC url to connect to.连接的JDBC URL
   * @param fqTable - The fully-qualified table name (or paren'd SQL query) to use.
    *                要使用的完全限定的表名(或paren'd SQL查询)
   * @param requiredColumns - The names of the columns to SELECT.SELECT列的名称
   * @param filters - The filters to include in all WHERE clauses.
    *                要包含在所有WHERE子句中的过滤器
   * @param parts - An array of JDBCPartitions specifying partition ids and
   *    per-partition WHERE clauses.
   *
   * @return An RDD representing "SELECT requiredColumns FROM fqTable".
   */
  def scanTable(
      sc: SparkContext,
      schema: StructType,
      driver: String,
      url: String,
      properties: Properties,
      fqTable: String,
      requiredColumns: Array[String],
      filters: Array[Filter],
      parts: Array[Partition]): RDD[InternalRow] = {
    val dialect = JdbcDialects.get(url)
    val quotedColumns = requiredColumns.map(colName => dialect.quoteIdentifier(colName))
    new JDBCRDD(
      sc,
      getConnector(driver, url, properties),
      pruneSchema(schema, requiredColumns),
      fqTable,
      quotedColumns,
      filters,
      parts,
      properties)
  }
}

/**
 * An RDD representing a table in a database accessed via JDBC.  Both the
 * driver code and the workers must be able to access the database; the driver
 * needs to fetch the schema while the workers need to fetch the data.
  * 表示通过JDBC访问的数据库中的表的RDD,驱动程序代码和工作者都必须能够访问数据库;
  * 当工作节点需要获取数据时,驱动程序需要获取模式。
 */
private[sql] class JDBCRDD(
    sc: SparkContext,
    getConnection: () => Connection,
    schema: StructType,
    fqTable: String,
    columns: Array[String],
    filters: Array[Filter],
    partitions: Array[Partition],
    properties: Properties)
  extends RDD[InternalRow](sc, Nil) {

  /**
   * Retrieve the list of partitions corresponding to this RDD.
    * 检索与此RDD对应的分区列表
   */
  override def getPartitions: Array[Partition] = partitions

  /**
   * `columns`, but as a String suitable for injection into a SQL query.
    * `columns`，但作为一个适合注入SQL查询的String
   */
  private val columnList: String = {
    val sb = new StringBuilder()
    columns.foreach(x => sb.append(",").append(x))
    if (sb.length == 0) "1" else sb.substring(1)
  }

  /**
   * Converts value to SQL expression.
    * 将值转换为SQL表达式
   */
  private def compileValue(value: Any): Any = value match {
    case stringValue: String => s"'${escapeSql(stringValue)}'"
    case timestampValue: Timestamp => "'" + timestampValue + "'"
    case dateValue: Date => "'" + dateValue + "'"
    case _ => value
  }

  private def escapeSql(value: String): String =
    if (value == null) null else StringUtils.replace(value, "'", "''")

  /**
   * Turns a single Filter into a String representing a SQL expression.
   * Returns null for an unhandled filter.
    * 将单个Filter转换为表示SQL表达式的String,对于未处理的过滤器,返回null
   */
  private def compileFilter(f: Filter): String = f match {
    case EqualTo(attr, value) => s"$attr = ${compileValue(value)}"
    case LessThan(attr, value) => s"$attr < ${compileValue(value)}"
    case GreaterThan(attr, value) => s"$attr > ${compileValue(value)}"
    case LessThanOrEqual(attr, value) => s"$attr <= ${compileValue(value)}"
    case GreaterThanOrEqual(attr, value) => s"$attr >= ${compileValue(value)}"
    case _ => null
  }

  /**
   * `filters`, but as a WHERE clause suitable for injection into a SQL query.
    * `filters`,但作为适合注入SQL查询的WHERE子句
   */
  private val filterWhereClause: String = {
    val filterStrings = filters map compileFilter filter (_ != null)
    if (filterStrings.size > 0) {
      val sb = new StringBuilder("WHERE ")
      filterStrings.foreach(x => sb.append(x).append(" AND "))
      sb.substring(0, sb.length - 5)
    } else ""
  }

  /**
   * A WHERE clause representing both `filters`, if any, and the current partition.
    * 一个WHERE子句,表示“过滤器”(如果有)和当前分区
   */
  private def getWhereClause(part: JDBCPartition): String = {
    if (part.whereClause != null && filterWhereClause.length > 0) {
      filterWhereClause + " AND " + part.whereClause
    } else if (part.whereClause != null) {
      "WHERE " + part.whereClause
    } else {
      filterWhereClause
    }
  }

  // Each JDBC-to-Catalyst conversion corresponds to a tag defined here so that
  // we don't have to potentially poke around in the Metadata once for every
  // row.
  //每个JDBC-to-Catalyst转换对应于此处定义的标记,这样我们就不必每次都在元数据中找到一次
  // Is there a better way to do this?  I'd rather be using a type that
  // contains only the tags I define.
  abstract class JDBCConversion
  case object BooleanConversion extends JDBCConversion
  case object DateConversion extends JDBCConversion
  case class  DecimalConversion(precision: Int, scale: Int) extends JDBCConversion
  case object DoubleConversion extends JDBCConversion
  case object FloatConversion extends JDBCConversion
  case object IntegerConversion extends JDBCConversion
  case object LongConversion extends JDBCConversion
  case object BinaryLongConversion extends JDBCConversion
  case object StringConversion extends JDBCConversion
  case object TimestampConversion extends JDBCConversion
  case object BinaryConversion extends JDBCConversion

  /**
   * Maps a StructType to a type tag list.
    * 将StructType映射到类型标记列表
   */
  def getConversions(schema: StructType): Array[JDBCConversion] = {
    schema.fields.map(sf => sf.dataType match {
      case BooleanType => BooleanConversion
      case DateType => DateConversion
      case DecimalType.Fixed(p, s) => DecimalConversion(p, s)
      case DoubleType => DoubleConversion
      case FloatType => FloatConversion
      case IntegerType => IntegerConversion
      case LongType =>
        if (sf.metadata.contains("binarylong")) BinaryLongConversion else LongConversion
      case StringType => StringConversion
      case TimestampType => TimestampConversion
      case BinaryType => BinaryConversion
      case _ => throw new IllegalArgumentException(s"Unsupported field $sf")
    }).toArray
  }

  /**
   * Runs the SQL query against the JDBC driver.
    * 针对JDBC驱动程序运行SQL查询
   *
   */
  override def compute(thePart: Partition, context: TaskContext): Iterator[InternalRow] =
    new Iterator[InternalRow] {
    var closed = false
    var finished = false
    var gotNext = false
    var nextValue: InternalRow = null

    context.addTaskCompletionListener{ context => close() }
    val part = thePart.asInstanceOf[JDBCPartition]
    val conn = getConnection()

    // H2's JDBC driver does not support the setSchema() method.  We pass a
    // fully-qualified table name in the SELECT statement.  I don't know how to
    // talk about a table in a completely portable way.

    val myWhereClause = getWhereClause(part)

    val sqlText = s"SELECT $columnList FROM $fqTable $myWhereClause"
    val stmt = conn.prepareStatement(sqlText,
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
    val fetchSize = properties.getProperty("fetchsize", "0").toInt
    stmt.setFetchSize(fetchSize)
    val rs = stmt.executeQuery()

    val conversions = getConversions(schema)
    val mutableRow = new SpecificMutableRow(schema.fields.map(x => x.dataType))

    def getNext(): InternalRow = {
      if (rs.next()) {
        var i = 0
        while (i < conversions.length) {
          val pos = i + 1
          conversions(i) match {
            case BooleanConversion => mutableRow.setBoolean(i, rs.getBoolean(pos))
            case DateConversion =>
              // DateTimeUtils.fromJavaDate does not handle null value, so we need to check it.
              val dateVal = rs.getDate(pos)
              if (dateVal != null) {
                mutableRow.setInt(i, DateTimeUtils.fromJavaDate(dateVal))
              } else {
                mutableRow.update(i, null)
              }
            // When connecting with Oracle DB through JDBC, the precision and scale of BigDecimal
            // object returned by ResultSet.getBigDecimal is not correctly matched to the table
            // schema reported by ResultSetMetaData.getPrecision and ResultSetMetaData.getScale.
            // If inserting values like 19999 into a column with NUMBER(12, 2) type, you get through
            // a BigDecimal object with scale as 0. But the dataframe schema has correct type as
            // DecimalType(12, 2). Thus, after saving the dataframe into parquet file and then
            // retrieve it, you will get wrong result 199.99.
            // So it is needed to set precision and scale for Decimal based on JDBC metadata.
            case DecimalConversion(p, s) =>
              val decimalVal = rs.getBigDecimal(pos)
              if (decimalVal == null) {
                mutableRow.update(i, null)
              } else {
                mutableRow.update(i, Decimal(decimalVal, p, s))
              }
            case DoubleConversion => mutableRow.setDouble(i, rs.getDouble(pos))
            case FloatConversion => mutableRow.setFloat(i, rs.getFloat(pos))
            case IntegerConversion => mutableRow.setInt(i, rs.getInt(pos))
            case LongConversion => mutableRow.setLong(i, rs.getLong(pos))
            // TODO(davies): use getBytes for better performance, if the encoding is UTF-8
            case StringConversion => mutableRow.update(i, UTF8String.fromString(rs.getString(pos)))
            case TimestampConversion =>
              val t = rs.getTimestamp(pos)
              if (t != null) {
                mutableRow.setLong(i, DateTimeUtils.fromJavaTimestamp(t))
              } else {
                mutableRow.update(i, null)
              }
            case BinaryConversion => mutableRow.update(i, rs.getBytes(pos))
            case BinaryLongConversion => {
              val bytes = rs.getBytes(pos)
              var ans = 0L
              var j = 0
              while (j < bytes.size) {
                ans = 256 * ans + (255 & bytes(j))
                j = j + 1;
              }
              mutableRow.setLong(i, ans)
            }
          }
          if (rs.wasNull) mutableRow.setNullAt(i)
          i = i + 1
        }
        mutableRow
      } else {
        finished = true
        null.asInstanceOf[InternalRow]
      }
    }

    def close() {
      if (closed) return
      try {
        if (null != rs) {
          rs.close()
        }
      } catch {
        case e: Exception => logWarning("Exception closing resultset", e)
      }
      try {
        if (null != stmt) {
          stmt.close()
        }
      } catch {
        case e: Exception => logWarning("Exception closing statement", e)
      }
      try {
        if (null != conn) {
          conn.close()
        }
        logInfo("closed connection")
      } catch {
        case e: Exception => logWarning("Exception closing connection", e)
      }
      closed = true
    }

    override def hasNext: Boolean = {
      if (!finished) {
        if (!gotNext) {
          nextValue = getNext()
          if (finished) {
            close()
          }
          gotNext = true
        }
      }
      !finished
    }

    override def next(): InternalRow = {
      if (!hasNext) {
        throw new NoSuchElementException("End of stream")
      }
      gotNext = false
      nextValue
    }
  }
}
