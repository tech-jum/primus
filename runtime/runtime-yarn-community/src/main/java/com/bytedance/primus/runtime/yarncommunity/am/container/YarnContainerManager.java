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

package com.bytedance.primus.runtime.yarncommunity.am.container;

import static com.bytedance.primus.am.container.ContainerManagerEventType.FORCIBLY_SHUTDOWN;
import static com.bytedance.primus.am.container.ContainerManagerEventType.GRACEFUL_SHUTDOWN;
import static org.apache.hadoop.yarn.api.records.ExecutionType.GUARANTEED;

import com.bytedance.blacklist.BlacklistTracker;
import com.bytedance.primus.am.AMContext;
import com.bytedance.primus.am.ApplicationExitCode;
import com.bytedance.primus.am.container.ContainerManager;
import com.bytedance.primus.am.container.ContainerManagerEvent;
import com.bytedance.primus.am.role.RoleInfoManager;
import com.bytedance.primus.am.schedule.strategy.ContainerScheduleChainManager;
import com.bytedance.primus.am.schedule.strategy.ContainerScheduleContext;
import com.bytedance.primus.am.schedulerexecutor.SchedulerExecutor;
import com.bytedance.primus.am.schedulerexecutor.SchedulerExecutorManager;
import com.bytedance.primus.am.schedulerexecutor.SchedulerExecutorManagerContainerCompletedEvent;
import com.bytedance.primus.am.schedulerexecutor.SchedulerExecutorManagerEventType;
import com.bytedance.primus.common.metrics.PrimusMetrics;
import com.bytedance.primus.runtime.yarncommunity.utils.YarnConvertor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.commons.configuration.Configuration;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.ContainerUpdateType;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.UpdateContainerRequest;
import org.apache.hadoop.yarn.api.records.UpdatedContainer;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class YarnContainerManager extends ContainerManager {

  private static final Logger LOG = LoggerFactory.getLogger(YarnContainerManager.class);
  private static final long ALLOCATE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10);

  protected AMContext context;
  protected AMRMClient<ContainerRequest> amRMClient;
  protected RoleInfoManager roleInfoManager;
  protected SchedulerExecutorManager schedulerExecutorManager;
  protected ContainerScheduleChainManager containerScheduleChainManager;

  protected Map<Integer, ConcurrentSkipListSet<ContainerId>> priorityContainerIdsMap = new ConcurrentHashMap<>();
  protected Map<ContainerId, Container> runningContainerMap = new ConcurrentHashMap<>();
  protected Queue<Container> containersToBeRelease = new ConcurrentLinkedQueue<>();

  protected Set<String> currentNodeBlacklist = new ConcurrentSkipListSet<>();
  protected Set<String> blacklistAdditions = new HashSet<>();
  protected Set<String> blacklistRemovals = new HashSet<>();

  protected volatile boolean isStopped = false;
  protected volatile boolean isShuttingDown = false;
  protected Thread containerManagerThread = new ContainerManagerThread();

  public YarnContainerManager(
      AMContext context,
      AMRMClient<ContainerRequest> amRMClient,
      RoleInfoManager roleInfoManager
  ) {
    super(YarnContainerManager.class.getName());

    this.context = context;
    this.amRMClient = amRMClient;
    this.roleInfoManager = roleInfoManager;
    this.schedulerExecutorManager = context.getSchedulerExecutorManager();
    this.containerScheduleChainManager = new ContainerScheduleChainManager(roleInfoManager);
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    super.serviceInit(conf);
  }

  @Override
  protected void serviceStart() throws Exception {
    // XXX: Meta redirect doesn't work well with YARN community,
    // so we don't have the API path here in the Tracking URL.
    String trackingUrl = String.format(
        "http://%s:%d",
        context.getWebAppServerHostAddress(),
        context.getWebAppServerPort());

    RegisterApplicationMasterResponse response = amRMClient.registerApplicationMaster(
        context.getAmService().getHostName(),
        context.getAmService().getPort(),
        trackingUrl
    );

    LOG.info("Tracking URL is " + trackingUrl);
    containerManagerThread.start();
  }

  @Override
  protected void serviceStop() throws Exception {
    isStopped = true;
    containerManagerThread.interrupt();
    try {
      containerManagerThread.join();
    } catch (InterruptedException e) {
      // ignore
    }
    LOG.info("YarnContainerManagerService stopped");
    super.serviceStop();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void handle(ContainerManagerEvent event) {
    switch (event.getType()) {
      case CONTAINER_REQUEST_CREATED:
      case CONTAINER_REQUEST_UPDATED:
        updatePriorityContainerIdsMap();
        break;
      case EXECUTOR_EXPIRED: {
        containersToBeRelease.add(
            YarnConvertor.toYarnContainer(
                event.getContainer()));
        SchedulerExecutor schedulerExecutor =
            schedulerExecutorManager.getSchedulerExecutor(
                event.getContainer().getId().toString());
        if (schedulerExecutor != null) {
          handleReleasedContainer(
              YarnConvertor.toYarnContainer(event.getContainer()),
              schedulerExecutor.getExecutorExitCode(),
              schedulerExecutor.getExecutorExitMsg());
        }
        PrimusMetrics.emitCounterWithAppIdTag(
            "am.container_manager.executor_expired", new HashMap<>(), 1);
        break;
      }
      case GRACEFUL_SHUTDOWN:
      case FORCIBLY_SHUTDOWN:
        LOG.info("Start killing all running containers");
        isShuttingDown = true;
        runningContainerMap.keySet().stream()
            .map(cid -> schedulerExecutorManager.getSchedulerExecutor(cid.toString()))
            .filter(Objects::nonNull)
            .forEach(schedulerExecutor -> {
              if (event.getType() == GRACEFUL_SHUTDOWN) {
                LOG.info(
                    "Gracefully killing container: {}",
                    schedulerExecutor.getContainer().getId());
                context.emitExecutorKillEvent(schedulerExecutor.getExecutorId());
              } else if (event.getType() == FORCIBLY_SHUTDOWN) {
                LOG.info(
                    "Forcibly killing container: {}",
                    schedulerExecutor.getContainer().getId());
                context.emitExecutorKillForciblyEvent(schedulerExecutor.getExecutorId());
              }
            });
        break;
    }
  }

  protected void updatePriorityContainerIdsMap() {
    roleInfoManager
        .getRolePriorities()
        .forEach(priority -> priorityContainerIdsMap.putIfAbsent(
            priority, new ConcurrentSkipListSet<>())
        );
  }

  protected void handleReleasedContainers(List<ContainerStatus> containerStatuses) {
    containerStatuses.forEach(status -> {
      ContainerId containerId = status.getContainerId();
      LOG.info("Container " + containerId + " completed");

      Container container = runningContainerMap.remove(containerId);
      if (container == null) {
        LOG.warn(
            "Cannot find container in running container map, container id {}",
            status.getContainerId());
      } else {
        handleReleasedContainer(
            container,
            status.getExitStatus(),
            status.getDiagnostics());
      }
    });
  }

  protected void handleReleasedContainer(
      Container container,
      int exitStatus, String diag
  ) {
    int priority = container.getPriority().getPriority();
    ConcurrentSkipListSet<ContainerId> containerIds = priorityContainerIdsMap.get(priority);
    containerIds.remove(container.getId());
    Optional<BlacklistTracker> blacklistTrackerOpt = context.getBlacklistTracker();
    ContainerScheduleContext context = new ContainerScheduleContext(
        YarnConvertor.toPrimusContainer(container),
        exitStatus, diag, blacklistTrackerOpt);
    containerScheduleChainManager.processReleasedContainer(context);
    schedulerExecutorManager.handle(
        new SchedulerExecutorManagerContainerCompletedEvent(
            SchedulerExecutorManagerEventType.CONTAINER_RELEASED,
            YarnConvertor.toPrimusContainer(container),
            exitStatus, context.getErrMsg()));

    PrimusMetrics.emitCounterWithAppIdTag(
        "am.container_manager.release_container", new HashMap<>(), 1);
  }

  @SuppressWarnings("unchecked")
  private void handleYarnUpdatedContainers(List<UpdatedContainer> updatedContainers) {
    updatedContainers.forEach(updatedContainer -> {
          Container container = updatedContainer.getContainer();
          if (runningContainerMap.containsKey(container.getId())) {
            runningContainerMap.put(container.getId(), container);
          }
          LOG.info(
              "Receive updateResponse from Yarn, Container:{}, UpdateType:{}",
              container.getId().toString(), updatedContainer.getUpdateType());

          context.emitContainerUpdatedEvent(YarnConvertor.toPrimusContainer(container));
        }
    );
  }

  protected void logContainerUrl(Container container) {
    LOG.info("Allocate " + container.getId() + " on http://" + container.getNodeHttpAddress()
        + "/node/containerlogs/" + container.getId() + "/" + context.getApplicationMeta()
        .getUsername());
  }

  protected void abort(String diag) {
    context.emitFailAttemptEvent(diag, ApplicationExitCode.ABORT.getValue());
  }

  protected void finish() {
    LOG.info("All container complete");
    context.emitApplicationSuccessEvent(
        "All container complete",
        ApplicationExitCode.CONTAINER_COMPLETE.getValue()
    );
  }

  protected abstract void handleAllocation(AllocateResponse response);

  protected abstract void askForContainers();

  private void checkAndUpdateRunningContainers() {
    for (Container container : runningContainerMap.values()) {
      Resource target = ((YarnRoleInfo) roleInfoManager
          .getRoleInfo(container.getPriority().getPriority()))
          .getResource();

      /**
       * Yarn allocates memory in 1 Gib granularity
       * We resize target to yarnTarget here to avoid yarn IllegalArgumentException.
       *
       * e.g. If current resource of container is 8192 Mib (yarn round up allocation) and 4 cores,
       * target resource is 8000 Mib and 5 cores, we get null updateType.
       */
      Resource yarnTarget = Resource.newInstance(target);
      yarnTarget.setMemorySize(ResourceCalculator.roundUp(target.getMemorySize(), 1024));

      Resource currentResource = Resource.newInstance(container.getResource());
      currentResource.setMemorySize(
          ResourceCalculator.roundUp(currentResource.getMemorySize(), 1024));

      ContainerUpdateType updateType = getContainerUpdateType(currentResource, yarnTarget);
      if (updateType != null) {
        LOG.info("Request container update, originalResource: " + container.getResource()
            + ", yarnTargetResource: " + yarnTarget + ", updateType: " + updateType);
        UpdateContainerRequest updateContainerRequest =
            UpdateContainerRequest.newInstance(
                container.getVersion(),
                container.getId(),
                updateType,
                yarnTarget,
                GUARANTEED);

        amRMClient.requestContainerUpdate(container, updateContainerRequest);
      }
    }
  }

  // References:
  // org.apache.hadoop.yarn.client.api.impl.AMRMClientImpl#requestContainerUpdate
  // org.apache.hadoop.yarn.client.api.impl.AMRMClientImpl#validateContainerResourceChangeRequest
  private ContainerUpdateType getContainerUpdateType(Resource original, Resource target) {
    if (original == null
        || Resources.equals(Resources.none(), original)
        || !Resources.fitsIn(Resources.none(), original)
        || target == null
        || Resources.equals(Resources.none(), target)
        || !Resources.fitsIn(Resources.none(), target)) {
      return null;
    }
    if (Resources.fitsIn(original, target) && Resources.fitsIn(target, original)) {
      // can't use Resources.equals() because it compares resource units
      return null;
    } else if (Resources.fitsIn(target, original)) {
      return ContainerUpdateType.DECREASE_RESOURCE;
    } else if (Resources.fitsIn(original, target)) {
      return ContainerUpdateType.INCREASE_RESOURCE;
    }
    return null;
  }

  class ContainerManagerThread extends Thread {

    public ContainerManagerThread() {
      super(ContainerManagerThread.class.getName());
      setDaemon(true);
    }

    @Override
    public void run() {
      AtomicBoolean disableContainerSucceedAppLogFlag = new AtomicBoolean(true);
      while (!isStopped) {
        float progress = context.getProgressManager().getProgress();
        try {
          Set<String> latestNodeBlackList =
              context.getBlacklistTracker()
                  .map(b -> b.getNodeBlacklist().keySet())
                  .orElse(Collections.emptySet());
          blacklistAdditions.addAll(latestNodeBlackList);
          blacklistAdditions.removeAll(currentNodeBlacklist);
          blacklistRemovals.addAll(currentNodeBlacklist);
          blacklistRemovals.removeAll(latestNodeBlackList);
          if (!blacklistAdditions.isEmpty()) {
            LOG.info(
                "blacklistAdditions: "
                    + blacklistAdditions.stream().collect(Collectors.joining(",", "[", "]")));
          }
          if (!blacklistRemovals.isEmpty()) {
            LOG.info(
                "blacklistRemovals: "
                    + blacklistRemovals.stream().collect(Collectors.joining(",", "[", "]")));
          }
          amRMClient.updateBlacklist(
              new ArrayList<>(blacklistAdditions),
              new ArrayList<>(blacklistRemovals));
          currentNodeBlacklist.clear();
          blacklistAdditions.clear();
          blacklistRemovals.clear();
          currentNodeBlacklist.addAll(latestNodeBlackList);

          AllocateResponse response = amRMClient.allocate(progress);

          while (!containersToBeRelease.isEmpty()) {
            Container container = containersToBeRelease.poll();
            if (container != null) {
              amRMClient.releaseAssignedContainer(container.getId());
            }
          }

          handleAllocation(response);
          handleReleasedContainers(response.getCompletedContainersStatuses());
          if (context.getApplicationMeta().getPrimusConf().getScheduler()
              .getEnableUpdateResource()) {
            handleYarnUpdatedContainers(response.getUpdatedContainers());
            checkAndUpdateRunningContainers();
          }

          if (!isShuttingDown) {
            askForContainers();

            if (schedulerExecutorManager.isAllSuccess()) {
              finish();
            } else if (schedulerExecutorManager.isAllCompleted()) {
              String diag = "All executors completed but not success";
              LOG.error(diag);
              abort(diag);
            }
          }
          Thread.sleep(ALLOCATE_INTERVAL_MS);
        } catch (InterruptedException e) {
          // ignore
        } catch (Exception e) {
          String diag = "Container manager caught exception " + e;
          LOG.error(diag, e);
          abort(diag);
        }
      }
      LOG.info("ContainerManagerThread exited");
    }
  }
}
