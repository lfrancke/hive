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
package org.apache.hive.hcatalog.templeton.tool;

public interface JobSubmissionConstants {
  String COPY_NAME = "templeton.copy";
  String STATUSDIR_NAME = "templeton.statusdir";
  String ENABLE_LOG = "templeton.enablelog";
  String JOB_TYPE = "templeton.jobtype";
  String JAR_ARGS_NAME = "templeton.args";
  String TEMPLETON_JOB_LAUNCH_TIME_NAME = "templeton.job.launch.time";
  String OVERRIDE_CLASSPATH = "templeton.override-classpath";
  String STDOUT_FNAME = "stdout";
  String STDERR_FNAME = "stderr";
  String EXIT_FNAME = "exit";
  int WATCHER_TIMEOUT_SECS = 10;
  int KEEP_ALIVE_MSEC = 60 * 1000;
  /*
   * The = sign in the string for TOKEN_FILE_ARG_PLACEHOLDER is required because
   * org.apache.hadoop.util.GenericOptionsParser.preProcessForWindows() prepares
   * arguments expecting an = sign. It will fail to prepare the arguments correctly
   * without the = sign present.
   */ String TOKEN_FILE_ARG_PLACEHOLDER =
    "__MR_JOB_CREDENTIALS_OPTION=WEBHCAT_TOKEN_FILE_LOCATION__";
  String TOKEN_FILE_ARG_PLACEHOLDER_TEZ =
    "__TEZ_CREDENTIALS_OPTION=WEBHCAT_TOKEN_FILE_LOCATION_TEZ__";
  // MRv2 job tag used to identify Templeton launcher child jobs. Each child job
  // will be tagged with the parent jobid so that on launcher task restart, all
  // previously running child jobs can be killed before the child job is launched
  // again.
  String MAPREDUCE_JOB_TAGS = "mapreduce.job.tags";
  String MAPREDUCE_JOB_TAGS_ARG_PLACEHOLDER =
    "__MR_JOB_TAGS_OPTION=MR_JOB_TAGS_JOBID__";

  /**
   * constants needed for Pig job submission
   * The string values here are what Pig expects to see in it's environment
   */
  interface PigConstants {
    String HIVE_HOME = "HIVE_HOME";
    String HCAT_HOME = "HCAT_HOME";
    String PIG_OPTS = "PIG_OPTS";
  }
}
