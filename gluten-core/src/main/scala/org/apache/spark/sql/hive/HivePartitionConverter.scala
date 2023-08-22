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
package org.apache.spark.sql.hive

import io.glutenproject.backendsapi.BackendsApiManager

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.{InternalRow, SQLConfHelper}
import org.apache.spark.sql.catalyst.analysis.CastSupport
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.execution.PartitionedFileUtil
import org.apache.spark.sql.execution.datasources.{FilePartition, PartitionDirectory}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.DataType

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.hive.ql.metadata.{Partition => HivePartition}
import org.apache.hadoop.io.compress.CompressionCodecFactory

import java.net.URI

import scala.collection.JavaConverters.asScalaBufferConverter

class HivePartitionConverter(hadoopConf: Configuration, session: SparkSession)
  extends CastSupport
  with SQLConfHelper {
  override def conf: SQLConf = session.sessionState.conf
  private def castFromString(value: String, dataType: DataType) = {
    cast(Literal(value), dataType).eval(null)
  }

  lazy val codecFactory: CompressionCodecFactory =
    new CompressionCodecFactory(hadoopConf)
  private def canBeSplit(filePath: Path): Boolean = {
    // Checks if file at path `filePath` can be split.
    // Uncompressed Hive Text files may be split. GZIP compressed files are not.
    // Note: This method works on a Path, and cannot take a `FileStatus`.
    //       partition.files is an Array[FileStatus] on vanilla Apache Spark,
    //       but an Array[SerializableFileStatus] on Databricks.
    val codec = codecFactory.getCodec(filePath)
    codec == null || BackendsApiManager.getValidatorApiInstance.doCompressionSplittableValidate(
      codec.getClass.getSimpleName)
  }

  private def isNonEmptyDataFile(f: FileStatus): Boolean = {
    if (!f.isFile || f.getLen == 0) {
      false
    } else {
      val name = f.getPath.getName
      !((name.startsWith("_") && !name.contains("=")) || name.startsWith("."))
    }
  }

  private def listFiles(
      prunedPartitions: Seq[HivePartition],
      partitionColTypes: Seq[DataType]): Seq[PartitionDirectory] = {
    val directories = prunedPartitions.map {
      p =>
        // No need to check if partition directory exists.
        // FileSystem.listStatus() handles this for HiveTableScanExecTransformer,
        // just like for Apache Spark.
        val uri = p.getDataLocation.toUri
        val partValues: Seq[Any] = {
          p.getValues.asScala.zip(partitionColTypes).map {
            case (value, dataType) => castFromString(value, dataType)
          }
        }
        val partValuesAsInternalRow = InternalRow.fromSeq(partValues)

        (uri, partValuesAsInternalRow)
    }
    listFiles(directories)
  }

  private def listFiles(directories: Seq[(URI, InternalRow)]): Seq[PartitionDirectory] = {
    directories.map {
      case (directory, partValues) =>
        val path = new Path(directory)
        val fs = path.getFileSystem(hadoopConf)
        val dirContents = fs.listStatus(path).filter(isNonEmptyDataFile)
        PartitionDirectory(partValues, dirContents)
    }
  }

  private def createFilePartition(
      selectedPartitions: Seq[PartitionDirectory]): Seq[FilePartition] = {
    val maxSplitBytes = FilePartition.maxSplitBytes(session, selectedPartitions)
    val splitFiles = selectedPartitions.flatMap {
      partition =>
        partition.files
          .flatMap {
            f =>
              PartitionedFileUtil.splitFiles(
                session,
                f,
                f.getPath,
                isSplitable = canBeSplit(f.getPath),
                maxSplitBytes,
                partition.values
              )
          }
          .sortBy(_.length)(implicitly[Ordering[Long]].reverse)
    }
    FilePartition.getFilePartitions(session, splitFiles, maxSplitBytes)
  }

  def createFilePartition(
      prunedPartitions: Seq[HivePartition],
      partitionColTypes: Seq[DataType]): Seq[FilePartition] = {
    createFilePartition(listFiles(prunedPartitions, partitionColTypes))
  }

  def createFilePartition(tableLocation: URI): Seq[FilePartition] =
    createFilePartition(listFiles(Seq((tableLocation, InternalRow.empty))))
}