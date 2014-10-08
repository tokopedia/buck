/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * {@link BuildRule} that wraps a file generated by another rule so that there can be a
 * {@link BuildRuleSourcePath} that corresponds to that file. This is frequently used with
 * rules/flavors that are generated via graph enhancement.
 */
public class OutputOnlyBuildRule extends AbstractBuildRule {

  public static final BuildRuleType TYPE = new BuildRuleType("output_only_build_rule");

  private final Path pathToOutputFile;

  public OutputOnlyBuildRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Path pathToOutputFile) {
    super(buildRuleParams, resolver);
    this.pathToOutputFile = Preconditions.checkNotNull(pathToOutputFile);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(pathToOutputFile);

    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutputFile() {
    return pathToOutputFile;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  protected Builder appendDetailsToRuleKey(Builder builder) {
    // Note that the path itself is part of the rule key, but not the contents of the file.
    return builder.set("output", pathToOutputFile.toString());
  }

}
