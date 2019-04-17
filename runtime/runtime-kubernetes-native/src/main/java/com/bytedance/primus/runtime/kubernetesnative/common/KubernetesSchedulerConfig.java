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

package com.bytedance.primus.runtime.kubernetesnative.common;

import static com.bytedance.primus.runtime.kubernetesnative.common.constants.KubernetesConstants.K8S_SCHEDULE_QUEUE_NAME_ANNOTATION_VALUE_DEFAULT;
import static com.bytedance.primus.runtime.kubernetesnative.common.constants.KubernetesConstants.K8S_SCHEDULE_SCHEDULER_NAME_ANNOTATION_VALUE_DEFAULT;
import static com.bytedance.primus.runtime.kubernetesnative.common.constants.KubernetesConstants.K8S_SCHEDULE_SERVICE_ACCOUNT_NAME_DEFAULT;

import com.bytedance.primus.common.util.StringUtils;
import com.bytedance.primus.proto.PrimusConfOuterClass.PrimusConf;
import com.bytedance.primus.proto.PrimusRuntime.KubernetesScheduler;
import com.bytedance.primus.runtime.kubernetesnative.common.constants.KubernetesConstants;

// TODO: Maybe it's a better idea to bake the values into PrimusConf upfront during merging
//  PrimusConf, so that it will be easier to share across multiple components and less error-prone.
public class KubernetesSchedulerConfig {

  private final String namespace;
  private final String serviceAccountName;
  private final String schedulerName;
  private final String queue;

  public KubernetesSchedulerConfig(PrimusConf primusConf) {
    KubernetesScheduler kubernetesScheduler = primusConf
        .getScheduler()
        .getKubernetesScheduler();

    namespace = StringUtils.ensure(
        kubernetesScheduler.getNamespace(),
        KubernetesConstants.PRIMUS_DEFAULT_K8S_NAMESPACE);
    serviceAccountName = StringUtils.ensure(
        kubernetesScheduler.getServiceAccountName(),
        K8S_SCHEDULE_SERVICE_ACCOUNT_NAME_DEFAULT);
    schedulerName = StringUtils.ensure(
        kubernetesScheduler.getSchedulerName(),
        K8S_SCHEDULE_SCHEDULER_NAME_ANNOTATION_VALUE_DEFAULT);
    queue = StringUtils.ensure(
        kubernetesScheduler.getQueue(),
        K8S_SCHEDULE_QUEUE_NAME_ANNOTATION_VALUE_DEFAULT);
  }

  public String getNamespace() {
    return namespace;
  }

  public String getServiceAccountName() {
    return serviceAccountName;
  }

  public String getSchedulerName() {
    return schedulerName;
  }

  public String getQueue() {
    return queue;
  }
}