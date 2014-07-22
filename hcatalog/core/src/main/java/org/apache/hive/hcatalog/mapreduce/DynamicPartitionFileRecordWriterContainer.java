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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hive.hcatalog.mapreduce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.metadata.HiveStorageHandler;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hive.hcatalog.common.ErrorType;
import org.apache.hive.hcatalog.common.HCatException;
import org.apache.hive.hcatalog.common.HCatUtil;
import org.apache.hive.hcatalog.data.HCatRecord;

/**
 * Record writer container for tables using dynamic partitioning. See
 * {@link FileOutputFormatContainer} for more information
 */
class DynamicPartitionFileRecordWriterContainer extends FileRecordWriterContainer {
  private final List<Integer> dynamicPartCols;
  private int maxDynamicPartitions;

  private final Map<String, RecordWriter<? super WritableComparable<?>, ? super Writable>> baseDynamicWriters;
  private final Map<String, SerDe> baseDynamicSerDe;
  private final Map<String, org.apache.hadoop.mapred.OutputCommitter> baseDynamicCommitters;
  private final Map<String, org.apache.hadoop.mapred.TaskAttemptContext> dynamicContexts;
  private final Map<String, ObjectInspector> dynamicObjectInspectors;
  private Map<String, OutputJobInfo> dynamicOutputJobInfo;

  /**
   * @param baseWriter RecordWriter to contain
   * @param context current TaskAttemptContext
   * @throws IOException
   * @throws InterruptedException
   */
  public DynamicPartitionFileRecordWriterContainer(
      RecordWriter<? super WritableComparable<?>, ? super Writable> baseWriter,
      TaskAttemptContext context) throws IOException, InterruptedException {
    super(baseWriter, context);
    maxDynamicPartitions = jobInfo.getMaxDynamicPartitions();
    dynamicPartCols = jobInfo.getPosOfDynPartCols();
    if (dynamicPartCols == null) {
      throw new HCatException("It seems that setSchema() is not called on "
          + "HCatOutputFormat. Please make sure that method is called.");
    }

    this.baseDynamicSerDe = new HashMap<String, SerDe>();
    this.baseDynamicWriters =
        new HashMap<String, RecordWriter<? super WritableComparable<?>, ? super Writable>>();
    this.baseDynamicCommitters = new HashMap<String, org.apache.hadoop.mapred.OutputCommitter>();
    this.dynamicContexts = new HashMap<String, org.apache.hadoop.mapred.TaskAttemptContext>();
    this.dynamicObjectInspectors = new HashMap<String, ObjectInspector>();
    this.dynamicOutputJobInfo = new HashMap<String, OutputJobInfo>();
  }

  @Override
  public void close(TaskAttemptContext context) throws IOException, InterruptedException {
    Reporter reporter = InternalUtil.createReporter(context);
    for (RecordWriter<? super WritableComparable<?>, ? super Writable> bwriter : baseDynamicWriters
        .values()) {
      // We are in RecordWriter.close() make sense that the context would be
      // TaskInputOutput.
      bwriter.close(reporter);
    }
    for (Map.Entry<String, org.apache.hadoop.mapred.OutputCommitter> entry : baseDynamicCommitters
        .entrySet()) {
      org.apache.hadoop.mapred.TaskAttemptContext currContext = dynamicContexts.get(entry.getKey());
      OutputCommitter baseOutputCommitter = entry.getValue();
      if (baseOutputCommitter.needsTaskCommit(currContext)) {
        baseOutputCommitter.commitTask(currContext);
      }
    }
  }

  @Override
  protected LocalFileWriter getLocalFileWriter(HCatRecord value) throws IOException, HCatException {
    OutputJobInfo localJobInfo = null;
    // Calculate which writer to use from the remaining values - this needs to
    // be done before we delete cols.
    List<String> dynamicPartValues = new ArrayList<String>();
    for (Integer colToAppend : dynamicPartCols) {
      dynamicPartValues.add(value.get(colToAppend).toString());
    }

    String dynKey = dynamicPartValues.toString();
    if (!baseDynamicWriters.containsKey(dynKey)) {
      if ((maxDynamicPartitions != -1) && (baseDynamicWriters.size() > maxDynamicPartitions)) {
        throw new HCatException(ErrorType.ERROR_TOO_MANY_DYNAMIC_PTNS,
            "Number of dynamic partitions being created "
                + "exceeds configured max allowable partitions[" + maxDynamicPartitions
                + "], increase parameter [" + HiveConf.ConfVars.DYNAMICPARTITIONMAXPARTS.varname
                + "] if needed.");
      }

      org.apache.hadoop.mapred.TaskAttemptContext currTaskContext =
          HCatMapRedUtil.createTaskAttemptContext(context);
      configureDynamicStorageHandler(currTaskContext, dynamicPartValues);
      localJobInfo = HCatBaseOutputFormat.getJobInfo(currTaskContext.getConfiguration());

      // Setup serDe.
      SerDe currSerDe =
          ReflectionUtils.newInstance(storageHandler.getSerDeClass(), currTaskContext.getJobConf());
      try {
        InternalUtil.initializeOutputSerDe(currSerDe, currTaskContext.getConfiguration(),
            localJobInfo);
      } catch (SerDeException e) {
        throw new IOException("Failed to initialize SerDe", e);
      }

      // create base OutputFormat
      org.apache.hadoop.mapred.OutputFormat baseOF =
          ReflectionUtils.newInstance(storageHandler.getOutputFormatClass(),
              currTaskContext.getJobConf());

      // We are skipping calling checkOutputSpecs() for each partition
      // As it can throw a FileAlreadyExistsException when more than one
      // mapper is writing to a partition.
      // See HCATALOG-490, also to avoid contacting the namenode for each new
      // FileOutputFormat instance.
      // In general this should be ok for most FileOutputFormat implementations
      // but may become an issue for cases when the method is used to perform
      // other setup tasks.

      // Get Output Committer
      org.apache.hadoop.mapred.OutputCommitter baseOutputCommitter =
          currTaskContext.getJobConf().getOutputCommitter();

      // Create currJobContext the latest so it gets all the config changes
      org.apache.hadoop.mapred.JobContext currJobContext =
          HCatMapRedUtil.createJobContext(currTaskContext);

      // Set up job.
      baseOutputCommitter.setupJob(currJobContext);

      // Recreate to refresh jobConf of currTask context.
      currTaskContext =
          HCatMapRedUtil.createTaskAttemptContext(currJobContext.getJobConf(),
              currTaskContext.getTaskAttemptID(), currTaskContext.getProgressible());

      // Set temp location.
      currTaskContext.getConfiguration().set(
          "mapred.work.output.dir",
          new FileOutputCommitter(new Path(localJobInfo.getLocation()), currTaskContext)
              .getWorkPath().toString());

      // Set up task.
      baseOutputCommitter.setupTask(currTaskContext);

      Path parentDir = new Path(currTaskContext.getConfiguration().get("mapred.work.output.dir"));
      Path childPath =
          new Path(parentDir, FileOutputFormat.getUniqueFile(currTaskContext, "part", ""));

      RecordWriter baseRecordWriter =
          baseOF.getRecordWriter(parentDir.getFileSystem(currTaskContext.getConfiguration()),
              currTaskContext.getJobConf(), childPath.toString(),
              InternalUtil.createReporter(currTaskContext));

      baseDynamicWriters.put(dynKey, baseRecordWriter);
      baseDynamicSerDe.put(dynKey, currSerDe);
      baseDynamicCommitters.put(dynKey, baseOutputCommitter);
      dynamicContexts.put(dynKey, currTaskContext);
      dynamicObjectInspectors.put(dynKey,
          InternalUtil.createStructObjectInspector(jobInfo.getOutputSchema()));
      dynamicOutputJobInfo.put(dynKey,
          HCatOutputFormat.getJobInfo(dynamicContexts.get(dynKey).getConfiguration()));
    }

    return new LocalFileWriter(baseDynamicWriters.get(dynKey), dynamicObjectInspectors.get(dynKey),
        baseDynamicSerDe.get(dynKey), dynamicOutputJobInfo.get(dynKey));
  }

  protected void configureDynamicStorageHandler(JobContext context, List<String> dynamicPartVals)
      throws IOException {
    HCatOutputFormat.configureOutputStorageHandler(context, dynamicPartVals);
  }
}
