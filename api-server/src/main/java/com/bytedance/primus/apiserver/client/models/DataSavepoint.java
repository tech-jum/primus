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

package com.bytedance.primus.apiserver.client.models;

import com.bytedance.primus.apiserver.proto.DataProto;
import com.bytedance.primus.apiserver.records.DataSavepointSpec;
import com.bytedance.primus.apiserver.records.DataSavepointStatus;
import com.bytedance.primus.apiserver.records.Meta;
import com.bytedance.primus.apiserver.records.Resource;
import com.bytedance.primus.apiserver.records.impl.DataSavepointSpecImpl;
import com.bytedance.primus.apiserver.records.impl.DataSavepointStatusImpl;
import com.bytedance.primus.apiserver.service.exception.ApiServerException;
import com.bytedance.primus.apiserver.service.exception.ErrorCode;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;

public class DataSavepoint extends
    AbstractApiType<DataSavepoint, DataSavepointSpec, DataSavepointStatus> {

  public static final String KIND = "DataSavepoint";

  private Meta meta;
  private DataSavepointSpec spec;
  private DataSavepointStatus status;

  @Override
  public String getKind() {
    return KIND;
  }

  @Override
  public Meta getMeta() {
    return meta;
  }

  @Override
  public DataSavepoint setMeta(Meta meta) {
    this.meta = meta;
    return this;
  }

  @Override
  public DataSavepointSpec getSpec() {
    return spec;
  }

  @Override
  public DataSavepoint setSpec(DataSavepointSpec spec) {
    this.spec = spec;
    return this;
  }

  @Override
  public DataSavepointStatus getStatus() {
    return status;
  }

  @Override
  public DataSavepoint setStatus(DataSavepointStatus status) {
    this.status = status;
    return this;
  }

  @Override
  public Resource toResource() {
    Any specAny = spec != null ? Any.pack(spec.getProto()) : null;
    Any statusAny = status != null ? Any.pack(status.getProto()) : null;
    return toResourceImpl(KIND, meta, specAny, statusAny);
  }

  @Override
  public DataSavepoint fromResource(Resource resource) throws ApiServerException {
    if (resource.getKind().equals(this.KIND)) {
      try {
        meta = resource.getMeta();
        spec = new DataSavepointSpecImpl(
            resource.getSpec().unpack(DataProto.DataSavepointSpec.class));
        // ensure status has been set
        if (resource.getStatus().is(DataProto.DataSavepointStatus.class)) {
          status = new DataSavepointStatusImpl(
              resource.getStatus().unpack(DataProto.DataSavepointStatus.class));
        } else {
          status = new DataSavepointStatusImpl();
        }
        return this;
      } catch (InvalidProtocolBufferException e) {
        throw new ApiServerException(e);
      }
    } else {
      throw new ApiServerException(ErrorCode.INVALID_ARGUMENT,
          "Incompatible resource kind, required " + this.KIND + ", provided" + resource.getKind());
    }
  }

  @Override
  public String toString() {
    MessageOrBuilder metaMsg = meta != null ? meta.getProto() : null;
    MessageOrBuilder specMsg = spec != null ? spec.getProto() : null;
    MessageOrBuilder statusMsg = status != null ? status.getProto() : null;
    return toStringImpl(KIND, metaMsg, specMsg, statusMsg);
  }
}
