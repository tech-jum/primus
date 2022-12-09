/*
 * Copyright 2022 Bytedance Inc.
 *
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

package com.bytedance.primus.io.datasource.file.impl.customized;

import com.bytedance.primus.io.datasource.file.FileDataSource;
import com.bytedance.primus.io.messagebuilder.MessageBuilder;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.RecordReader;

// CustomizedFileDataSource is an example for demonstrating how to customize a FileDataSource.
public class CustomizedFileDataSource implements FileDataSource {

  private final String keyPrefix;
  private final String valPrefix;
  private final String keySuffix;
  private final String valSuffix;

  public CustomizedFileDataSource(Map<String, String> params) {
    keyPrefix = params.getOrDefault("key-prefix", "key-prefix");
    valPrefix = params.getOrDefault("val-prefix", "val-prefix");
    keySuffix = params.getOrDefault("key-suffix", "key-suffix");
    valSuffix = params.getOrDefault("val-suffix", "val-suffix");
  }

  public RecordReader<Object, Object> createRecordReader(
      Configuration hadoopConf,
      FileSplit fileSplit
  ) throws Exception {
    return new CustomizedRecordReader(fileSplit, hadoopConf, keyPrefix, valPrefix);
  }

  public MessageBuilder createMessageBuilder(int messageBufferSize) {
    return new CustomizedMessageBuilder(messageBufferSize, keySuffix, valSuffix);
  }
}

