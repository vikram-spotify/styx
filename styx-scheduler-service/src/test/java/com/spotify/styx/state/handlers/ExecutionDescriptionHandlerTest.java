/*-
 * -\-\-
 * Spotify Styx Scheduler Service
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

package com.spotify.styx.state.handlers;

import static com.github.npathai.hamcrestopt.OptionalMatchers.hasValue;
import static com.spotify.styx.model.Schedule.HOURS;
import static com.spotify.styx.state.RunState.State.PREPARE;
import static com.spotify.styx.testdata.TestData.FULL_WORKFLOW_CONFIGURATION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.spotify.styx.model.Event;
import com.spotify.styx.model.EventVisitor;
import com.spotify.styx.model.ExecutionDescription;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowConfiguration;
import com.spotify.styx.model.WorkflowConfigurationBuilder;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.state.RunState;
import com.spotify.styx.state.StateManager;
import com.spotify.styx.storage.Storage;
import com.spotify.styx.util.WorkflowValidator;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ExecutionDescriptionHandlerTest {

  private static final String DOCKER_IMAGE = "my_docker_image";
  private static final String COMMIT_SHA = "71d70fca99e29812e81d1ed0a5c9d3559f4118e9";
  private static final Instant NOW = Instant.now();
  private static final long COUNTER = 17;

  private ExecutionDescriptionHandler toTest;

  @Mock Storage storage;
  @Mock StateManager stateManager;
  @Mock EventVisitor<Void> eventVisitor;

  @Captor ArgumentCaptor<WorkflowInstance> workflowInstanceCaptor;
  @Captor ArgumentCaptor<ExecutionDescription> executionDescriptionCaptor;
  @Captor ArgumentCaptor<String> executionIdCaptor;
  @Captor ArgumentCaptor<Event> eventCaptor;

  @Mock WorkflowValidator workflowValidator;

  @Before
  public void setUp() throws Exception {
    when(workflowValidator.validateWorkflow(any())).thenReturn(Collections.emptyList());

    toTest = new ExecutionDescriptionHandler(storage, stateManager, workflowValidator);
  }

  @Test
  public void shouldTransitionIntoSubmittingIfMissingDockerArgs() throws Exception {
    Workflow workflow = Workflow.create("id", workflowConfiguration());
    WorkflowInstance workflowInstance = WorkflowInstance.create(workflow.id(), "2016-03-14");
    RunState runState = RunState.create(workflowInstance, PREPARE, NOW, COUNTER);

    when(storage.workflow(workflow.id())).thenReturn(Optional.of(workflow));

    toTest.transitionInto(runState);

    verify(stateManager).receive(eventCaptor.capture(), eq(COUNTER));

    final Event event = eventCaptor.getValue();
    event.accept(eventVisitor);
    verify(eventVisitor)
        .submit(workflowInstanceCaptor.capture(), executionDescriptionCaptor.capture(), executionIdCaptor.capture());

    assertThat(executionIdCaptor.getValue(), startsWith("styx-run-"));
    assertThat(executionDescriptionCaptor.getValue().dockerImage(), is(DOCKER_IMAGE));
    assertThat(executionDescriptionCaptor.getValue().dockerArgs(), hasSize(0));
    assertThat(executionDescriptionCaptor.getValue().commitSha(), hasValue(COMMIT_SHA));
  }

  @Test
  public void shouldTransitionIntoSubmitting() throws Exception {
    Workflow workflow = Workflow.create("id", workflowConfiguration("--date", "{}", "--bar"));
    WorkflowInstance workflowInstance = WorkflowInstance.create(workflow.id(), "2016-03-14");
    RunState runState = RunState.create(workflowInstance, PREPARE, NOW, COUNTER);

    when(storage.workflow(workflow.id())).thenReturn(Optional.of(workflow));

    toTest.transitionInto(runState);

    verify(stateManager).receive(eventCaptor.capture(), eq(COUNTER));

    final Event event = eventCaptor.getValue();
    event.accept(eventVisitor);

    verify(eventVisitor)
        .submit(workflowInstanceCaptor.capture(), executionDescriptionCaptor.capture(), executionIdCaptor.capture());

    assertThat(executionIdCaptor.getValue(), startsWith("styx-run-"));
    assertThat(executionDescriptionCaptor.getValue().dockerImage(), is(DOCKER_IMAGE));
    assertThat(executionDescriptionCaptor.getValue().commitSha(), hasValue(COMMIT_SHA));
    assertThat(executionDescriptionCaptor.getValue().dockerArgs(), contains("--date", "2016-03-14", "--bar"));
  }

  @Test
  public void shouldTransitionIntoFailedIfStorageError() throws Exception {
    Workflow workflow = Workflow.create("id", workflowConfiguration("--date", "{}", "--bar"));
    WorkflowInstance workflowInstance = WorkflowInstance.create(workflow.id(), "2016-03-14");

    IOException exception = new IOException("TEST");
    when(storage.workflow(workflow.id()))
        .thenThrow(exception);

    RunState runState = RunState.create(workflowInstance, PREPARE, NOW, COUNTER);

    toTest.transitionInto(runState);

    verify(stateManager).receive(Event.runError(workflowInstance, exception.getMessage()), COUNTER);
  }

  @Test
  public void shouldHaltIfMissingWorkflow() throws Exception {
    WorkflowInstance workflowInstance = WorkflowInstance.create(WorkflowId.create("c", "e"), "2016-03-14T15");
    RunState runState = RunState.create(workflowInstance, PREPARE, NOW, COUNTER);

    when(storage.workflow(any())).thenReturn(Optional.empty());

    toTest.transitionInto(runState);

    verify(stateManager).receiveIgnoreClosed(Event.halt(workflowInstance), COUNTER);
  }

  @Test
  public void shouldHaltIfMissingDockerImage() throws Exception {
    WorkflowConfiguration workflowConfiguration =
        WorkflowConfigurationBuilder.from(workflowConfiguration("foo", "bar"))
            .dockerImage(Optional.empty())
            .build();
    Workflow workflow = Workflow.create("id", workflowConfiguration);
    WorkflowInstance workflowInstance = WorkflowInstance.create(workflow.id(), "2016-03-14T15");
    RunState runState = RunState.create(workflowInstance, PREPARE, NOW, COUNTER);

    when(storage.workflow(workflow.id())).thenReturn(Optional.of(workflow));

    toTest.transitionInto(runState);

    verify(stateManager).receiveIgnoreClosed(Event.halt(workflowInstance), COUNTER);
  }

  @Test
  public void shouldHaltIfInvalidConfiguration() throws Exception {
    when(workflowValidator.validateWorkflow(any())).thenReturn(List.of("foo", "bar"));

    Workflow workflow = Workflow.create("id", FULL_WORKFLOW_CONFIGURATION);
    WorkflowInstance workflowInstance = WorkflowInstance.create(workflow.id(), "2016-03-14T15");
    RunState runState = RunState.create(workflowInstance, PREPARE, NOW, COUNTER);

    when(storage.workflow(workflow.id())).thenReturn(Optional.of(workflow));

    toTest.transitionInto(runState);

    verify(stateManager).receiveIgnoreClosed(Event.halt(workflowInstance), COUNTER);
  }

  private WorkflowConfiguration workflowConfiguration(String... args) {
    return WorkflowConfiguration.builder()
        .id("styx.TestEndpoint")
        .schedule(HOURS)
        .commitSha(COMMIT_SHA)
        .dockerImage(DOCKER_IMAGE)
        .dockerArgs(Arrays.asList(args))
        .build();
  }
}
