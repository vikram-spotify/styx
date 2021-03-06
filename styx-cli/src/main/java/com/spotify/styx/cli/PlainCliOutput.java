/*-
 * -\-\-
 * Spotify Styx CLI
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx.cli;

import static com.spotify.styx.cli.CliUtil.formatMap;
import static com.spotify.styx.cli.CliUtil.formatTimestamp;

import com.google.common.base.Joiner;
import com.spotify.styx.api.BackfillPayload;
import com.spotify.styx.api.RunStateDataPayload;
import com.spotify.styx.model.Backfill;
import com.spotify.styx.model.Resource;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowState;
import com.spotify.styx.model.data.EventInfo;
import com.spotify.styx.state.Message;
import com.spotify.styx.state.StateData;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * Cli output printer that prints more unix tool friendly output
 */
class PlainCliOutput implements CliOutput {

  @Override
  public void printStates(RunStateDataPayload runStateDataPayload) {
    SortedMap<WorkflowId, SortedSet<RunStateDataPayload.RunStateData>> groupedStates =
        CliUtil.groupStates(runStateDataPayload.activeStates());

    groupedStates.forEach((workflowId, value) -> value.forEach(RunStateData -> {
      final StateData stateData = RunStateData.stateData();
      System.out.println(String.format(
          "%s %s %s %s %s %d %s",
          workflowId.componentId(),
          workflowId.id(),
          RunStateData.workflowInstance().parameter(),
          RunStateData.state(),
          stateData.executionId().orElse("<no-execution-id>"),
          stateData.tries(),
          stateData.message().map(Message::line).orElse("No info")
      ));
    }));
  }

  @Override
  public void printEvents(List<EventInfo> eventInfos) {
    eventInfos.forEach(
        eventInfo ->
            System.out.println(String.format("%s %s %s",
                                             formatTimestamp(eventInfo.timestamp()),
                                             eventInfo.name(),
                                             eventInfo.info()))
    );
  }

  @Override
  public void printBackfill(Backfill backfill, boolean ignored) {
    System.out.println(String.format("%s %s %s %s %s %s %s %s %s %s %s %s",
        backfill.id(),
        backfill.workflowId().componentId(),
        backfill.workflowId().id(),
        backfill.halted(),
        backfill.allTriggered(),
        backfill.concurrency(),
        backfill.start(),
        backfill.end(),
        backfill.reverse(),
        backfill.nextTrigger(),
        backfill.description().orElse(""),
        backfill.triggerParameters().map(triggerParameters -> formatMap(
            triggerParameters.env())).orElse("")));
  }

  @Override
  public void printBackfillPayload(BackfillPayload backfillPayload, boolean ignored) {
    printBackfill(backfillPayload.backfill(), true);
    if (backfillPayload.statuses().isPresent()) {
      printStates(backfillPayload.statuses().get());
    }
  }

  @Override
  public void printBackfills(List<BackfillPayload> backfills, boolean ignored) {
    backfills.forEach(backfill -> printBackfillPayload(backfill, true));
  }

  @Override
  public void printResources(List<Resource> resources) {
    resources.forEach(resource ->
                          System.out.println(String.format("%s %s",
                                                           resource.id(),
                                                           resource.concurrency())));
  }

  @Override
  public void printMessage(String message) {
    System.out.println(message);
  }

  @Override
  public void printWorkflow(Workflow wf, WorkflowState state) {
    System.out.println(Joiner.on(' ').join(
        wf.componentId(),
        wf.workflowId(),
        wf.configuration().schedule(),
        wf.configuration().offset().orElse(""),
        wf.configuration().dockerImage().orElse(""),
        wf.configuration().dockerArgs().orElse(Collections.emptyList()),
        wf.configuration().dockerTerminationLogging(),
        wf.configuration().secret().map(s -> s.name() + ':' + s.mountPath()).orElse(""),
        wf.configuration().serviceAccount().map(Object::toString).orElse(""),
        wf.configuration().resources(),
        wf.configuration().env(),
        wf.configuration().runningTimeout().map(Duration::toString).orElse(""),
        wf.configuration().commitSha().orElse(""),
        state.enabled().map(Object::toString).orElse(""),
        state.nextNaturalTrigger().map(Object::toString).orElse(""),
        state.nextNaturalOffsetTrigger().map(Object::toString).orElse("")));
  }

  @Override
  public void printWorkflows(List<Workflow> workflows) {
    workflows.forEach(wf -> System.out.println(wf.componentId() + " " + wf.workflowId()));
  }

  @Override
  public void printError(String message) {
    System.err.println(message);
  }
}
