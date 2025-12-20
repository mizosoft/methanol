/*
 * Copyright (c) 2025 Moataz Hussein
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.osgi.test;

import aQute.bnd.build.Workspace;
import aQute.bnd.build.model.BndEditModel;
import aQute.bnd.deployer.repository.LocalIndexedRepo;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Jar;
import aQute.bnd.service.RepositoryPlugin;
import biz.aQute.resolve.Bndrun;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.mizosoft.methanol.testing.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(TestUtils.VERY_SLOW_TIMEOUT_SECONDS)
class OsgiResolutionTest {
  private static final List<String> EXCLUDED_BUNDLES =
      List.of(
          "methanol-redis", // Depends on lettuce-core which doesn't have OSGi metadata.
          "methanol-moshi", // Depends on moshi which doesn't have OSGi metadata.
          "methanol-kotlin", // Depends on Kotlin stdlib which may not have proper OSGi metadata.
          "methanol-jaxb" // JAXB activation dependencies have resolution issues.
          );

  private static final Map<String, Path> BUNDLE_PATHS =
      OsgiUtils.BUNDLE_PATHS.entrySet().stream()
          .filter(entry -> !EXCLUDED_BUNDLES.contains(entry.getKey()))
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

  private static final String RESOLVE_OSGI_FRAMEWORK = "org.eclipse.osgi";
  private static final String RESOLVE_JAVA_VERSION =
      System.getProperty("com.github.mizosoft.methanol.osgi.test.javaVersion", "JavaSE-11");
  private static final String REPO_NAME = "MethanolOsgiTest";

  private Path workspaceDir;
  private Workspace workspace;

  @BeforeEach
  @Timeout(TestUtils.VERY_SLOW_TIMEOUT_SECONDS)
  void setUp(@TempDir Path workspaceDir) throws Exception {
    this.workspaceDir = workspaceDir;

    // Create bnd workspace.
    var bndDir = Files.createDirectory(workspaceDir.resolve("cnf"));
    var repoDir = Files.createDirectory(bndDir.resolve("repo"));

    workspace = new Workspace(workspaceDir.toFile(), bndDir.getFileName().toString());
    workspace.setProperty(
        Constants.PLUGIN + "." + REPO_NAME,
        String.format(
            "%s; %s=%s; %s='%s'",
            LocalIndexedRepo.class.getName(),
            LocalIndexedRepo.PROP_NAME,
            REPO_NAME,
            LocalIndexedRepo.PROP_LOCAL_DIR,
            repoDir));
    workspace.refresh();

    var repository = workspace.getRepository(REPO_NAME);

    // Deploy methanol bundles.
    for (var bundlePath : BUNDLE_PATHS.values()) {
      deployFile(repository, bundlePath);
    }

    // Deploy dependencies from classpath.
    deployClassPath(repository);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (workspace != null) {
      workspace.close();
      if (Files.exists(workspaceDir)) {
        try (var paths = Files.walk(workspaceDir)) {
          paths.forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException ignored) {
                  // Skip.
                }
              });
        }
      }
    }
  }

  @Test
  void resolveAllMethanolBundles() throws Exception {
    try (var bndRun = createBndRun()) {
      bndRun.resolve(false, false);
    }
  }

  private Bndrun createBndRun() throws Exception {
    // Create run require string for all methanol bundles.
    var runRequireString =
        BUNDLE_PATHS.keySet().stream()
            .map(bundle -> "osgi.identity;filter:='(osgi.identity=" + bundle + ")'")
            .reduce((a, b) -> a + "," + b)
            .orElse("");

    var bndRun = new Bndrun(new BndEditModel(workspace));
    bndRun.setRunfw(RESOLVE_OSGI_FRAMEWORK);
    bndRun.setRunee(RESOLVE_JAVA_VERSION);
    bndRun.setRunRequires(runRequireString);
    return bndRun;
  }

  private void deployClassPath(RepositoryPlugin repository) {
    for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      var path = Path.of(entry);
      if (Files.isRegularFile(path)) {
        deployFile(repository, path);
      }
    }
  }

  private void deployFile(RepositoryPlugin repository, Path file) {
    if (!Files.isRegularFile(file)) {
      return;
    }

    try (var jar = new Jar(file.toFile())) {
      if (jar.getManifest() == null
          || jar.getManifest().getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME)
              == null) {
        // Skip non-OSGi JARs silently.
        return;
      }

      // Deploy to repository.
      try (var in = new FileInputStream(file.toFile())) {
        repository.put(in, new RepositoryPlugin.PutOptions());
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
