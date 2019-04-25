/*
 * -\-\-
 * Spotify Styx Service Common
 * --
 * Copyright (C) 2019 Spotify AB
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

import com.google.common.base.Preconditions;
import com.spotify.styx.model.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Additional workflow validation can only be done on service side.
 */
public class ExtraWorkflowValidator implements WorkflowValidator {

  private static final Duration MIN_RUNNING_TIMEOUT = Duration.ofMinutes(1);

  private final WorkflowValidator workflowValidator;
  private final Duration maxRunningTimeout;
  private final Set<String> secretWhitelist;

  public ExtraWorkflowValidator(WorkflowValidator workflowValidator, Duration maxRunningTimeout,
                                Set<String> secretWhitelist) {
    Preconditions.checkArgument(maxRunningTimeout != null && !maxRunningTimeout.isNegative(),
        "Max Running timeout should be positive");
    this.workflowValidator = Objects.requireNonNull(workflowValidator);
    this.maxRunningTimeout = maxRunningTimeout;
    this.secretWhitelist = Objects.requireNonNull(secretWhitelist);
  }

  @Override
  public List<String> validateWorkflow(Workflow workflow) {
    var e = new ArrayList<>(workflowValidator.validateWorkflow(workflow));

    var cfg = workflow.configuration();

    cfg.runningTimeout().ifPresent(timeout -> {
      lowerLimit(e, timeout, MIN_RUNNING_TIMEOUT, "running timeout is too small");
      if (maxRunningTimeout != null) {
        upperLimit(e, timeout, maxRunningTimeout, "running timeout is too big");
      }
    });

    cfg.secret().ifPresent(secret -> {
      if (secretWhitelist != null && !secretWhitelist.contains(secret.name())) {
        e.add("secret " + secret.name() + " is not whitelisted");
      }
    });

    return e;
  }
}
