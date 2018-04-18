/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sqoop.hive;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.sqoop.io.CodecMap;

import org.apache.sqoop.SqoopOptions;
import org.apache.sqoop.manager.ConnManager;
import org.apache.sqoop.util.FileSystemUtil;

/**
 * Creates (Hive-specific) SQL DDL statements to create tables to hold data
 * we're importing from another source.
 *
 * After we import the database into HDFS, we can inject it into Hive using
 * the CREATE TABLE and LOAD DATA INPATH statements generated by this object.
 */
public class TableDefWriter {

  public static final Log LOG = LogFactory.getLog(
      TableDefWriter.class.getName());

  private SqoopOptions options;
  private ConnManager connManager;
  private Configuration configuration;
  private String inputTableName;
  private String outputTableName;
  private boolean commentsEnabled;

  /**
   * Creates a new TableDefWriter to generate a Hive CREATE TABLE statement.
   * @param opts program-wide options
   * @param connMgr the connection manager used to describe the table.
   * @param inputTable the name of the table to load.
   * @param outputTable the name of the Hive table to create.
   * @param config the Hadoop configuration to use to connect to the dfs
   * @param withComments if true, then tables will be created with a
   *        timestamp comment.
   */
  public TableDefWriter(final SqoopOptions opts, final ConnManager connMgr,
      final String inputTable, final String outputTable,
      final Configuration config, final boolean withComments) {
    this.options = opts;
    this.connManager = connMgr;
    this.inputTableName = inputTable;
    this.outputTableName = outputTable;
    this.configuration = config;
    this.commentsEnabled = withComments;
  }

  /**
   * Get the column names to import.
   */
  private String [] getColumnNames() {
    String [] colNames = options.getColumns();
    if (null != colNames) {
      return colNames; // user-specified column names.
    } else if (null != inputTableName) {
      return connManager.getColumnNames(inputTableName);
    } else {
      return connManager.getColumnNamesForQuery(options.getSqlQuery());
    }
  }

  /**
   * @return the CREATE TABLE statement for the table to load into hive.
   */
  public String getCreateTableStmt() throws IOException {
    resetConnManager();
    Map<String, Integer> columnTypes;
    Properties userMapping = options.getMapColumnHive();
    Boolean isHiveExternalTableSet = !StringUtils.isBlank(options.getHiveExternalTableDir());
    // Get these from the database.
    if (null != inputTableName) {
      columnTypes = connManager.getColumnTypes(inputTableName);
    } else {
      columnTypes = connManager.getColumnTypesForQuery(options.getSqlQuery());
    }

    String [] colNames = getColumnNames();
    StringBuilder sb = new StringBuilder();
    if (options.doFailIfHiveTableExists()) {
      if (isHiveExternalTableSet) {
        sb.append("CREATE EXTERNAL TABLE `");
      } else {
        sb.append("CREATE TABLE `");
      }
    } else {
      if (isHiveExternalTableSet) {
        sb.append("CREATE EXTERNAL TABLE IF NOT EXISTS `");
      } else {
        sb.append("CREATE TABLE IF NOT EXISTS `");
      }
    }

    if(options.getHiveDatabaseName() != null) {
      sb.append(options.getHiveDatabaseName()).append("`.`");
    }
    sb.append(outputTableName).append("` ( ");

    // Check that all explicitly mapped columns are present in result set
    for(Object column : userMapping.keySet()) {
      boolean found = false;
      for(String c : colNames) {
        if (c.equals(column)) {
          found = true;
          break;
        }
      }

      if (!found) {
        throw new IllegalArgumentException("No column by the name " + column
                + "found while importing data");
      }
    }

    boolean first = true;
    String partitionKey = options.getHivePartitionKey();
    for (String col : colNames) {
      if (col.equals(partitionKey)) {
        throw new IllegalArgumentException("Partition key " + col + " cannot "
            + "be a column to import.");
      }

      if (!first) {
        sb.append(", ");
      }

      first = false;

      Integer colType = columnTypes.get(col);
      String hiveColType = userMapping.getProperty(col);
      if (hiveColType == null) {
        hiveColType = connManager.toHiveType(inputTableName, col, colType);
      }
      if (null == hiveColType) {
        throw new IOException("Hive does not support the SQL type for column "
            + col);
      }

      sb.append('`').append(col).append("` ").append(hiveColType);

      if (HiveTypes.isHiveTypeImprovised(colType)) {
        LOG.warn(
            "Column " + col + " had to be cast to a less precise type in Hive");
      }
    }

    sb.append(") ");

    if (commentsEnabled) {
      DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
      String curDateStr = dateFormat.format(new Date());
      sb.append("COMMENT 'Imported by sqoop on " + curDateStr + "' ");
    }

    if (partitionKey != null) {
      sb.append("PARTITIONED BY (")
        .append(partitionKey)
        .append(" STRING) ");
     }

    sb.append("ROW FORMAT DELIMITED FIELDS TERMINATED BY '");
    sb.append(getHiveOctalCharCode((int) options.getOutputFieldDelim()));
    sb.append("' LINES TERMINATED BY '");
    sb.append(getHiveOctalCharCode((int) options.getOutputRecordDelim()));
    String codec = options.getCompressionCodec();
    if (codec != null && (codec.equals(CodecMap.LZOP)
            || codec.equals(CodecMap.getCodecClassName(CodecMap.LZOP)))) {
      sb.append("' STORED AS INPUTFORMAT "
              + "'com.hadoop.mapred.DeprecatedLzoTextInputFormat'");
      sb.append(" OUTPUTFORMAT "
              + "'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat'");
    } else {
      sb.append("' STORED AS TEXTFILE");
    }

    if (isHiveExternalTableSet) {
      // add location
      sb.append(" LOCATION '"+options.getHiveExternalTableDir()+"'");
    }

    LOG.debug("Create statement: " + sb.toString());
    return sb.toString();
  }

  /**
   * @return the LOAD DATA statement to import the data in HDFS into hive.
   */
  public String getLoadDataStmt() throws IOException {
    Path finalPath = getFinalPath();

    StringBuilder sb = new StringBuilder();
    sb.append("LOAD DATA INPATH '");
    sb.append(finalPath.toString() + "'");
    if (options.doOverwriteHiveTable()) {
      sb.append(" OVERWRITE");
    }
    sb.append(" INTO TABLE `");
    if(options.getHiveDatabaseName() != null) {
      sb.append(options.getHiveDatabaseName()).append("`.`");
    }
    sb.append(outputTableName);
    sb.append('`');

    if (options.getHivePartitionKey() != null) {
      sb.append(" PARTITION (")
        .append(options.getHivePartitionKey())
        .append("='").append(options.getHivePartitionValue())
        .append("')");
    }

    LOG.debug("Load statement: " + sb.toString());
    return sb.toString();
  }

  public Path getFinalPath() throws IOException {
    String warehouseDir = options.getWarehouseDir();
    if (null == warehouseDir) {
      warehouseDir = "";
    } else if (!warehouseDir.endsWith(File.separator)) {
      warehouseDir = warehouseDir + File.separator;
    }

    // Final path is determined in the following order:
    // 1. Use target dir if the user specified.
    // 2. Use input table name.
    String tablePath = null;
    String targetDir = options.getTargetDir();
    if (null != targetDir) {
      tablePath = warehouseDir + targetDir;
    } else {
      tablePath = warehouseDir + inputTableName;
    }
    return FileSystemUtil.makeQualified(new Path(tablePath), configuration);
  }

  /**
   * Return a string identifying the character to use as a delimiter
   * in Hive, in octal representation.
   * Hive can specify delimiter characters in the form '\ooo' where
   * ooo is a three-digit octal number between 000 and 177. Values
   * may not be truncated ('\12' is wrong; '\012' is ok) nor may they
   * be zero-prefixed (e.g., '\0177' is wrong).
   *
   * @param charNum the character to use as a delimiter
   * @return a string of the form "\ooo" where ooo is an octal number
   * in [000, 177].
   * @throws IllegalArgumentException if charNum &gt; 0177.
   */
  public static String getHiveOctalCharCode(int charNum) {
    if (charNum > 0177) {
      throw new IllegalArgumentException(
          "Character " + charNum + " is an out-of-range delimiter");
    }

    return String.format("\\%03o", charNum);
  }

  /**
   * The JDBC connection owned by the ConnManager has been most probably opened when the import was started
   * so it might have timed out by the time TableDefWriter methods are invoked which happens at the end of import.
   * The task of this method is to discard the current connection held by ConnManager to make sure
   * that TableDefWriter will have a working one.
   */
  private void resetConnManager() {
    this.connManager.discardConnection(true);
  }

  SqoopOptions getOptions() {
    return options;
  }

  ConnManager getConnManager() {
    return connManager;
  }

  Configuration getConfiguration() {
    return configuration;
  }

  String getInputTableName() {
    return inputTableName;
  }

  String getOutputTableName() {
    return outputTableName;
  }

  boolean isCommentsEnabled() {
    return commentsEnabled;
  }
}

