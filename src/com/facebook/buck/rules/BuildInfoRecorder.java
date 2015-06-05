/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.Funnels;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Utility for recording the paths to the output files generated by a build rule, as well as any
 * metadata about those output files. This data will be packaged up into an artifact that will be
 * stored in the cache. The metadata will also be written to disk so it can be read on a subsequent
 * build by an {@link OnDiskBuildInfo}.
 */
public class BuildInfoRecorder {

  @VisibleForTesting
  static final String ABSOLUTE_PATH_ERROR_FORMAT =
      "Error! '%s' is trying to record artifacts with absolute path: '%s'.";

  private static final Path PATH_TO_ARTIFACT_INFO = Paths.get("buck-out/log/cache_artifact.txt");
  private static final String BUCK_CACHE_DATA_ENV_VAR = "BUCK_CACHE_DATA";

  private final BuildTarget buildTarget;
  private final Path pathToMetadataDirectory;
  private final ProjectFilesystem projectFilesystem;
  private final Clock clock;
  private final BuildId buildId;
  private final ImmutableMap<String, String> artifactExtraData;
  private final Map<String, String> metadataToWrite;
  private final RuleKey ruleKey;

  /**
   * Every value in this set is a path relative to the project root.
   */
  private final Set<Path> pathsToOutputs;

  BuildInfoRecorder(BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Clock clock,
      BuildId buildId,
      ImmutableMap<String, String> environment,
      RuleKey ruleKey,
      RuleKey rukeKeyWithoutDeps) {
    this.buildTarget = buildTarget;
    this.pathToMetadataDirectory = BuildInfo.getPathToMetadataDirectory(buildTarget);
    this.projectFilesystem = projectFilesystem;
    this.clock = clock;
    this.buildId = buildId;

    this.artifactExtraData =
        ImmutableMap.<String, String>builder()
            .put(
                "artifact_data",
                Optional.fromNullable(environment.get(BUCK_CACHE_DATA_ENV_VAR)).or("null"))
            .build();

    this.metadataToWrite = Maps.newHashMap();
    metadataToWrite.put(BuildInfo.METADATA_KEY_FOR_RULE_KEY,
        ruleKey.toString());
    metadataToWrite.put(BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS,
        rukeKeyWithoutDeps.toString());

    this.ruleKey = ruleKey;
    this.pathsToOutputs = Sets.newHashSet();
  }

  /**
   * Writes the metadata currently stored in memory to the directory returned by
   * {@link BuildInfo#getPathToMetadataDirectory(BuildTarget)}.
   */
  public void writeMetadataToDisk(boolean clearExistingMetadata) throws IOException {
    if (clearExistingMetadata) {
      projectFilesystem.rmdir(pathToMetadataDirectory);
    }
    projectFilesystem.mkdirs(pathToMetadataDirectory);

    for (Map.Entry<String, String> entry : metadataToWrite.entrySet()) {
      projectFilesystem.writeContentsToPath(
          entry.getValue(),
          pathToMetadataDirectory.resolve(entry.getKey()));
    }
  }

  /**
   * This key/value pair is stored in memory until {@link #writeMetadataToDisk(boolean)} is invoked.
   */
  public void addMetadata(String key, String value) {
    metadataToWrite.put(key, value);
  }

  public void addMetadata(String key, Iterable<String> value) {
    JsonArray values = new JsonArray();
    for (String str : value) {
      values.add(new JsonPrimitive(str));
    }
    addMetadata(key, values.toString());
  }

  private ImmutableSet<Path> getRecordedPaths() throws IOException {
    final ImmutableSet.Builder<Path> paths = ImmutableSortedSet.naturalOrder();

    // Add metadata files.
    paths.addAll(
        FluentIterable.from(metadataToWrite.keySet())
            .transform(MorePaths.TO_PATH)
            .transform(
                new Function<Path, Path>() {
                  @Override
                  public Path apply(Path input) {
                    return pathToMetadataDirectory.resolve(input);
                  }
                }));

    // Add files from output directories.
    for (final Path output : pathsToOutputs) {
      projectFilesystem.walkRelativeFileTree(
          output,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(
                Path file,
                BasicFileAttributes attrs)
                throws IOException {
              paths.add(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(
                Path dir,
                BasicFileAttributes attrs)
                throws IOException {
              paths.add(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    }

    return paths.build();
  }

  public Pair<Long, HashCode> getOutputSizeAndHash(HashFunction hashFunction) throws IOException {
    long size = 0;
    Hasher hasher = hashFunction.newHasher();
    for (Path path : getRecordedPaths()) {
      if (projectFilesystem.isFile(path)) {
        size += projectFilesystem.getFileSize(path);
        hasher.putString(path.toString(), Charsets.UTF_8);
        try (InputStream input = projectFilesystem.newFileInputStream(path)) {
          ByteStreams.copy(input, Funnels.asOutputStream(hasher));
        }
      }
    }
    return new Pair<>(size, hasher.hash());
  }

  private String formatAdditionalArtifactInfo(Map<String, String> entries) {
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      builder.append(entry.getKey());
      builder.append('=');
      builder.append(entry.getValue());
      builder.append('\n');
    }
    return builder.toString();
  }

  /**
   * Creates a zip file of the metadata and recorded artifacts and stores it in the artifact cache.
   */
  public void performUploadToArtifactCache(ArtifactCache artifactCache, BuckEventBus eventBus)
      throws InterruptedException {

    // Skip all of this if caching is disabled. Although artifactCache.store() will be a noop,
    // building up the zip is wasted I/O.
    if (!artifactCache.isStoreSupported()) {
      return;
    }

    eventBus.post(
        ArtifactCacheEvent.started(
            ArtifactCacheEvent.Operation.COMPRESS,
            ruleKey));

    String additionalArtifactInfo =
        formatAdditionalArtifactInfo(
            ImmutableMap.<String, String>builder()
                .put("build_id", buildId.toString())
                .put(
                    "timestamp",
                    String.valueOf(TimeUnit.MILLISECONDS.toSeconds(clock.currentTimeMillis())))
                .putAll(artifactExtraData)
                .build());

    File zip;
    ImmutableSet<Path> pathsToIncludeInZip = ImmutableSet.of();
    try {
      pathsToIncludeInZip = getRecordedPaths();
      zip = File.createTempFile(
          "buck_artifact_" + MoreFiles.sanitize(buildTarget.getShortName()),
          ".zip");
      projectFilesystem.createZip(
          pathsToIncludeInZip,
          zip,
          ImmutableMap.of(PATH_TO_ARTIFACT_INFO, additionalArtifactInfo));
    } catch (IOException e) {
      eventBus.post(ConsoleEvent.info("Failed to create zip for %s containing:\n%s",
          buildTarget,
          Joiner.on('\n').join(ImmutableSortedSet.copyOf(pathsToIncludeInZip))));
      e.printStackTrace();
      return;
    } finally {
      eventBus.post(
          ArtifactCacheEvent.finished(
              ArtifactCacheEvent.Operation.COMPRESS,
              ruleKey));
    }
    artifactCache.store(ruleKey, zip);
    zip.delete();
  }

  /**
   * Fetches the artifact associated with the {@link #buildTarget} for this class and writes it to
   * the specified {@code outputFile}.
   */
  public CacheResult fetchArtifactForBuildable(File outputFile, ArtifactCache artifactCache)
      throws InterruptedException {
    return artifactCache.fetch(ruleKey, outputFile);
  }

  /**
   * @param pathToArtifact Relative path to the project root.
   */
  public void recordArtifact(Path pathToArtifact) {
    Preconditions.checkArgument(
        !pathToArtifact.isAbsolute(),
        ABSOLUTE_PATH_ERROR_FORMAT,
        buildTarget,
        pathToArtifact);
    pathsToOutputs.add(pathToArtifact);
  }

  @Nullable
  @VisibleForTesting
  String getMetadataFor(String key) {
    return metadataToWrite.get(key);
  }
}
