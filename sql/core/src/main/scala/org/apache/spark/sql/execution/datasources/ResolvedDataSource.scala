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

package org.apache.spark.sql.execution.datasources

import java.util.ServiceLoader

import scala.collection.JavaConversions._
import scala.language.{existentials, implicitConversions}
import scala.util.{Success, Failure, Try}

import org.apache.hadoop.fs.Path

import org.apache.spark.Logging
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.{DataFrame, SaveMode, AnalysisException, SQLContext}
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.{CalendarIntervalType, StructType}
import org.apache.spark.util.Utils


case class ResolvedDataSource(provider: Class[_], relation: BaseRelation)


object ResolvedDataSource extends Logging {

  /** A map to maintain backward compatibility in case we move data sources around.
    * 我们移动数据源时保持向后兼容性的映射*/
  private val backwardCompatibilityMap = Map(
    "org.apache.spark.sql.jdbc" -> classOf[jdbc.DefaultSource].getCanonicalName,
    "org.apache.spark.sql.jdbc.DefaultSource" -> classOf[jdbc.DefaultSource].getCanonicalName,
    "org.apache.spark.sql.json" -> classOf[json.DefaultSource].getCanonicalName,
    "org.apache.spark.sql.json.DefaultSource" -> classOf[json.DefaultSource].getCanonicalName,
    "org.apache.spark.sql.parquet" -> classOf[parquet.DefaultSource].getCanonicalName,
    "org.apache.spark.sql.parquet.DefaultSource" -> classOf[parquet.DefaultSource].getCanonicalName
  )

  /** Given a provider name, look up the data source class definition.
    * 给定提供程序名称,查找数据源类定义*/
  def lookupDataSource(provider0: String): Class[_] = {
    val provider = backwardCompatibilityMap.getOrElse(provider0, provider0)
    val provider2 = s"$provider.DefaultSource"
    val loader = Utils.getContextOrSparkClassLoader
    val serviceLoader = ServiceLoader.load(classOf[DataSourceRegister], loader)

    serviceLoader.iterator().filter(_.shortName().equalsIgnoreCase(provider)).toList match {
      /** the provider format did not match any given registered aliases
        * 提供程序格式与任何给定的已注册别名不匹配*/
      case Nil => Try(loader.loadClass(provider)).orElse(Try(loader.loadClass(provider2))) match {
        case Success(dataSource) => dataSource
        case Failure(error) =>
          if (provider.startsWith("org.apache.spark.sql.hive.orc")) {
            throw new ClassNotFoundException(
              "The ORC data source must be used with Hive support enabled.", error)
          } else {
            throw new ClassNotFoundException(
              s"Failed to load class for data source: $provider.", error)
          }
      }
      /** there is exactly one registered alias
        * 只有一个注册别名*/
      case head :: Nil => head.getClass
      /** There are multiple registered aliases for the input
        * 输入有多个注册别名*/
      case sources => sys.error(s"Multiple sources found for $provider, " +
        s"(${sources.map(_.getClass.getName).mkString(", ")}), " +
        "please specify the fully qualified class name.")
    }
  }

  /** Create a [[ResolvedDataSource]] for reading data in.
    * 创建[[ResolvedDataSource]]以读取数据*/
  def apply(
      sqlContext: SQLContext,
      userSpecifiedSchema: Option[StructType],
      partitionColumns: Array[String],
      provider: String,
      options: Map[String, String]): ResolvedDataSource = {
    val clazz: Class[_] = lookupDataSource(provider)
    def className: String = clazz.getCanonicalName
    val relation = userSpecifiedSchema match {
      case Some(schema: StructType) => clazz.newInstance() match {
        case dataSource: SchemaRelationProvider =>
          dataSource.createRelation(sqlContext, new CaseInsensitiveMap(options), schema)
        case dataSource: HadoopFsRelationProvider =>
          val maybePartitionsSchema = if (partitionColumns.isEmpty) {
            None
          } else {
            Some(partitionColumnsSchema(schema, partitionColumns))
          }

          val caseInsensitiveOptions = new CaseInsensitiveMap(options)
          val paths = {
            val patternPath = new Path(caseInsensitiveOptions("path"))
            val fs = patternPath.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)
            val qualifiedPattern = patternPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
            SparkHadoopUtil.get.globPathIfNecessary(qualifiedPattern).map(_.toString).toArray
          }

          val dataSchema =
            StructType(schema.filterNot(f => partitionColumns.contains(f.name))).asNullable

          dataSource.createRelation(
            sqlContext,
            paths,
            Some(dataSchema),
            maybePartitionsSchema,
            caseInsensitiveOptions)
        case dataSource: org.apache.spark.sql.sources.RelationProvider =>
          throw new AnalysisException(s"$className does not allow user-specified schemas.")
        case _ =>
          throw new AnalysisException(s"$className is not a RelationProvider.")
      }

      case None => clazz.newInstance() match {
        case dataSource: RelationProvider =>
          dataSource.createRelation(sqlContext, new CaseInsensitiveMap(options))
        case dataSource: HadoopFsRelationProvider =>
          val caseInsensitiveOptions = new CaseInsensitiveMap(options)
          val paths = {
            val patternPath = new Path(caseInsensitiveOptions("path"))
            val fs = patternPath.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)
            val qualifiedPattern = patternPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
            SparkHadoopUtil.get.globPathIfNecessary(qualifiedPattern).map(_.toString).toArray
          }
          dataSource.createRelation(sqlContext, paths, None, None, caseInsensitiveOptions)
        case dataSource: org.apache.spark.sql.sources.SchemaRelationProvider =>
          throw new AnalysisException(
            s"A schema needs to be specified when using $className.")
        case _ =>
          throw new AnalysisException(
            s"$className is neither a RelationProvider nor a FSBasedRelationProvider.")
      }
    }
    new ResolvedDataSource(clazz, relation)
  }

  private def partitionColumnsSchema(
      schema: StructType,
      partitionColumns: Array[String]): StructType = {
    StructType(partitionColumns.map { col =>
      schema.find(_.name == col).getOrElse {
        throw new RuntimeException(s"Partition column $col not found in schema $schema")
      }
    }).asNullable
  }

  /** Create a [[ResolvedDataSource]] for saving the content of the given DataFrame.
    * 创建[[ResolvedDataSource]]以保存给定DataFrame的内容*/
  def apply(
      sqlContext: SQLContext,
      provider: String,
      partitionColumns: Array[String],
      mode: SaveMode,
      options: Map[String, String],
      data: DataFrame): ResolvedDataSource = {
    if (data.schema.map(_.dataType).exists(_.isInstanceOf[CalendarIntervalType])) {
      throw new AnalysisException("Cannot save interval data type into external storage.")
    }
    val clazz: Class[_] = lookupDataSource(provider)
    val relation = clazz.newInstance() match {
      case dataSource: CreatableRelationProvider =>
        dataSource.createRelation(sqlContext, mode, options, data)
      case dataSource: HadoopFsRelationProvider =>
        // Don't glob path for the write path.  The contracts here are:
        //不要写入路径的glob路径,这里的合同是：
        //  1. Only one output path can be specified on the write path;
        //      在写路径上只能指定一个输出路径;
        //  2. Output path must be a legal HDFS style file system path;
        //    输出路径必须是合法的HDFS样式文件系统路径;
        //  3. It's OK that the output path doesn't exist yet;
        //    输出路径尚不存在是可以的;
        val caseInsensitiveOptions = new CaseInsensitiveMap(options)
        val outputPath = {
          val path = new Path(caseInsensitiveOptions("path"))
          val fs = path.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)
          path.makeQualified(fs.getUri, fs.getWorkingDirectory)
        }
        val dataSchema = StructType(data.schema.filterNot(f => partitionColumns.contains(f.name)))
        val r = dataSource.createRelation(
          sqlContext,
          Array(outputPath.toString),
          Some(dataSchema.asNullable),
          Some(partitionColumnsSchema(data.schema, partitionColumns)),
          caseInsensitiveOptions)

        // For partitioned relation r, r.schema's column ordering can be different from the column
        // ordering of data.logicalPlan (partition columns are all moved after data column).  This
        // will be adjusted within InsertIntoHadoopFsRelation.
        //对于分区关系r,r.schema的列排序可能与data.logicalPlan的列排序不同(分区列都在数据列之后移动),
        //这将在InsertIntoHadoopFsRelation中调整
        sqlContext.executePlan(
          InsertIntoHadoopFsRelation(
            r,
            data.logicalPlan,
            mode)).toRdd
        r
      case _ =>
        sys.error(s"${clazz.getCanonicalName} does not allow create table as select.")
    }
    ResolvedDataSource(clazz, relation)
  }
}
