/*
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2018 Spotify AB
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

package com.spotify.styx.util;

import static com.spotify.styx.testdata.TestData.FULL_WORKFLOW_CONFIGURATION;
import static com.spotify.styx.util.WorkflowValidator.MAX_COMMIT_SHA_LENGTH;
import static com.spotify.styx.util.WorkflowValidator.MAX_ENV_SIZE;
import static com.spotify.styx.util.WorkflowValidator.MAX_ENV_VARS;
import static com.spotify.styx.util.WorkflowValidator.MAX_ID_LENGTH;
import static com.spotify.styx.util.WorkflowValidator.MAX_RESOURCES;
import static com.spotify.styx.util.WorkflowValidator.MAX_RESOURCE_LENGTH;
import static com.spotify.styx.util.WorkflowValidator.MAX_SECRET_MOUNT_PATH_LENGTH;
import static com.spotify.styx.util.WorkflowValidator.MAX_SECRET_NAME_LENGTH;
import static com.spotify.styx.util.WorkflowValidator.MAX_SERVICE_ACCOUNT_LENGTH;
import static com.spotify.styx.util.WorkflowValidator.MIN_RUNNING_TIMEOUT;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.spotify.styx.model.Schedule;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowConfiguration;
import com.spotify.styx.model.WorkflowConfiguration.Secret;
import com.spotify.styx.model.WorkflowConfigurationBuilder;
import com.spotify.styx.testdata.TestData;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnitParamsRunner.class)
public class WorkflowValidatorTest {

  private static final Duration EXCESSIVE_TIMEOUT = Duration.ofDays(365);
  private static final WorkflowConfiguration CONFIGURATION_WITH_EXCESSIVE_RUNTIME_TIMEOUT =
      WorkflowConfigurationBuilder.from(FULL_WORKFLOW_CONFIGURATION)
          .runningTimeout(EXCESSIVE_TIMEOUT)
          .build();

  @Mock
  private DockerImageValidator dockerImageValidator;

  private WorkflowValidator sut;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    when(dockerImageValidator.validateImageReference(anyString())).thenReturn(Collections.emptyList());
    sut = WorkflowValidator.newBuilder(dockerImageValidator).build();
  }

  @Test
  public void validateValidWorkflow() {
    assertThat(sut.validateWorkflow(Workflow.create("test", FULL_WORKFLOW_CONFIGURATION)), is(empty()));
  }

  @Test
  @Parameters({
      "*/15 10 * * 6",
      "* *  *  * *",
      "@hourly", "hourly", "hours",
      "@daily", "daily", "days",
      "@weekly", "weekly", "weeks",
      "@monthly", "monthly", "months",
      "@annually", "annually", "@yearly",
      "yearly", "years",
  })
  public void validateValidCron(String expression) {
    assertThat(sut.validateWorkflow(Workflow.create(
        "test", WorkflowConfigurationBuilder.from(FULL_WORKFLOW_CONFIGURATION)
            .schedule(Schedule.parse(expression))
            .build())),
        is(empty()));
  }

  @Test
  public void validateInvalidOffset() {
    final List<String> errors = sut.validateWorkflow(Workflow.create(
        "test", TestData.HOURLY_WORKFLOW_CONFIGURATION_WITH_INVALID_OFFSET));
    assertThat(errors, hasSize(1));
    assertThat(errors.get(0), startsWith("invalid offset"));
  }

  @Test
  public void validateInvalidDockerImage() {
    when(dockerImageValidator.validateImageReference(anyString())).thenReturn(List.of("foo", "bar"));
    final List<String> errors = sut.validateWorkflow(Workflow.create("test", FULL_WORKFLOW_CONFIGURATION));
    assertThat(errors, contains("invalid image: foo", "invalid image: bar"));
  }


  @Test
  public void validateInvalidWorkflow() {
    final String id = Strings.repeat("id", 1024);
    final String schedule = Strings.repeat("schedule", 1024);
    final String offset = Strings.repeat("offset", 1024);
    final String commitSha = Strings.repeat("sha", 1024);
    final List<String> args = IntStream.range(0, 100).mapToObj(i -> "arg-" + i).collect(toList());
    final Secret secret = Secret.create(Strings.repeat("foo", 1024), Strings.repeat("bar", 4711));
    final String serviceAccount = Strings.repeat("account", 1024);
    final List<String> resources = IntStream.range(0, 10)
        .mapToObj(i -> Strings.repeat("res-" + i, 100)).collect(toList());
    final Map<String, String> env = IntStream.range(0, 2000).boxed()
        .collect(toMap(i -> "env-var-" + i, i -> "env-val-" + i));
    final long envSize = env.entrySet().stream().mapToLong(e -> e.getKey().length() + e.getValue().length()).sum();
    final Duration runningTimeout = Duration.ofSeconds(59L);

    final WorkflowConfiguration invalidConfiguration = WorkflowConfiguration.builder()
        .id(id)
        .schedule(Schedule.parse(schedule))
        .offset(offset)
        .commitSha(commitSha)
        .dockerArgs(args)
        .secret(secret)
        .serviceAccount(serviceAccount)
        .resources(resources)
        .serviceAccount(serviceAccount)
        .env(env)
        .runningTimeout(runningTimeout)
        .build();

    final List<String> errors = sut.validateWorkflow(Workflow.create("test", invalidConfiguration));

    final List<String> expectedErrors = ImmutableList.<String>builder()
        .add(limit("id too long", id.length(), MAX_ID_LENGTH))
        .add("invalid schedule")
        .add(limit("commitSha too long", commitSha.length(), MAX_COMMIT_SHA_LENGTH))
        .add(limit("secret name too long", secret.name().length(), MAX_SECRET_NAME_LENGTH))
        .add(limit("secret mount path too long", secret.mountPath().length(), MAX_SECRET_MOUNT_PATH_LENGTH))
        .add(limit("service account too long", serviceAccount.length(), MAX_SERVICE_ACCOUNT_LENGTH))
        .add(limit("too many resources", resources.size(), MAX_RESOURCES))
        .add(resources.stream().map(r ->
            limit("resource name too long", r.length(), MAX_RESOURCE_LENGTH)).toArray(String[]::new))
        .add("invalid offset: Unable to parse offset period")
        .add(limit("too many env vars", env.size(), MAX_ENV_VARS))
        .add(limit("env too big", envSize, MAX_ENV_SIZE))
        .add(limit("running timeout is too small", runningTimeout, MIN_RUNNING_TIMEOUT))
        .build();

    assertThat(errors, containsInAnyOrder(expectedErrors.toArray()));
  }

  @Test
  public void shouldSkipMaxRunningTimeoutValidationByDefault() {
    final List<String> errors = sut.validateWorkflow(Workflow.create("test",
        CONFIGURATION_WITH_EXCESSIVE_RUNTIME_TIMEOUT));

    assertThat(errors, empty());
  }

  @Test
  public void shouldEnforceMaxRunningTimeoutLimitWhenSpecified() {
    final Duration maxRunningTimeout = Duration.ofHours(24);
    WorkflowValidator sut = WorkflowValidator.newBuilder(dockerImageValidator)
        .withMaxRunningTimeoutLimit(maxRunningTimeout)
        .build();

    final List<String> errors = sut.validateWorkflow(
        Workflow.create("test", CONFIGURATION_WITH_EXCESSIVE_RUNTIME_TIMEOUT));

    assertThat(errors, contains(limit("running timeout is too big", EXCESSIVE_TIMEOUT, maxRunningTimeout)));
  }

  @Test
  public void shouldNotAllowWorkflowIdMismatch() {
    var component = "test";
    var workflowConfiguration = WorkflowConfiguration.builder()
        .id("foo")
        .schedule(Schedule.HOURS)
        .build();
    var workflow = new Workflow() {
      @Override
      public String componentId() {
        return component;
      }

      @Override
      public String workflowId() {
        return "bar";
      }

      @Override
      public WorkflowConfiguration configuration() {
        return workflowConfiguration;
      }
    };
    var errors = sut.validateWorkflow(workflow);
    assertThat(errors, contains("workflow id mismatch"));
  }

  @Test
  public void shouldNotAllowEmptyWorkflowId() {
    var workflowConfiguration = WorkflowConfiguration.builder()
        .id("")
        .schedule(Schedule.HOURS)
        .build();
    var errors = sut.validateWorkflow(Workflow.create("test", workflowConfiguration));
    assertThat(errors, contains("workflow id cannot be empty"));
  }

  @Test
  public void shouldNotAllowEmptyComponent() {
    var errors = sut.validateWorkflow(Workflow.create("", FULL_WORKFLOW_CONFIGURATION));
    assertThat(errors, contains("component id cannot be empty"));
  }

  @Parameters({"foo#bar", "#", "##"})
  @Test
  public void shouldNotAllowComponentWithHash(String component) {
    assertThat(sut.validateWorkflow(Workflow.create(component, FULL_WORKFLOW_CONFIGURATION)),
        contains("component id cannot contain #"));
  }

  @Test
  public void shouldFailUsageOfNonWhitelistedSecret() {
    WorkflowValidator sut = WorkflowValidator.newBuilder(dockerImageValidator)
        .withSecretWhitelist(Set.of("bar-secret"))
        .build();

    final List<String> errors = sut.validateWorkflow(
        Workflow.create("test", FULL_WORKFLOW_CONFIGURATION));

    assertThat(errors, contains("secret " + FULL_WORKFLOW_CONFIGURATION.secret().get().name() + " is not whitelisted"));
  }

  @Test
  public void shouldPassUsageOfWhitelistedSecret() {
    WorkflowValidator sut = WorkflowValidator.newBuilder(dockerImageValidator)
        .withSecretWhitelist(Set.of(FULL_WORKFLOW_CONFIGURATION.secret().get().name()))
        .build();

    final List<String> errors = sut.validateWorkflow(
        Workflow.create("test", FULL_WORKFLOW_CONFIGURATION));

    assertThat(errors, empty());
  }

  private String limit(String msg, Object value, Object limit) {
    return msg + ": " + value + ", limit = " + limit;
  }
}
