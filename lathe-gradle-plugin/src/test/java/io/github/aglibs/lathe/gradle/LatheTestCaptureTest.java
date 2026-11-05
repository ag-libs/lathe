package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheTestCaptureTest {

  @TempDir Path tmp;

  // A launch captured by Develocity's test-selection JVM names jars it copied to the temporary
  // directory, deleted later in the build: one there or already gone is temporary; the build's own
  // jars and Gradle's caches (either may live in the temporary directory too), the repository's,
  // and an output directory never created are not. A launch that can't be read is not checked.
  @Test
  void temporaryJar_launcherCopyOrGone_namesIt_buildCachesAndDirectoriesNot() throws IOException {
    final Path tmpDir = tmp.resolve("tmp");
    final Path rootDir = tmpDir.resolve("build-root");
    final Path userHome = tmpDir.resolve("gradle-home");
    final String inBuild = jar(rootDir.resolve("lib/build/libs/lib.jar"));
    final String inCaches = jar(userHome.resolve("caches/modules-2/junit.jar"));
    final String inRepository = jar(tmp.resolve("repository/junit.jar"));
    final String neverBuilt = rootDir.resolve("app/build/resources/main").toString();
    final String launcherCopy = jar(tmpDir.resolve("test-launcher/opentest4j-1.3.0.jar"));
    final String gone = tmp.resolve("repository/gone.jar").toString();
    final List<Path> keptDirs = List.of(rootDir, userHome);
    final Path unreadable = Files.writeString(tmp.resolve("broken.json"), "{");

    assertThat(temporaryJar(List.of(inBuild, inCaches, inRepository, neverBuilt), keptDirs, tmpDir))
        .isEmpty();
    assertThat(temporaryJar(List.of(inBuild, launcherCopy), keptDirs, tmpDir))
        .contains(launcherCopy);
    assertThat(temporaryJar(List.of(inRepository, gone), keptDirs, tmpDir)).contains(gone);
    assertThat(LatheTestCapture.temporaryJar(unreadable.toFile(), keptDirs, tmpDir)).isEmpty();
  }

  private Optional<String> temporaryJar(
      final List<String> classPath, final List<Path> keptDirs, final Path tmpDir)
      throws IOException {
    return LatheTestCapture.temporaryJar(launch(classPath), keptDirs, tmpDir);
  }

  private static String jar(final Path jar) throws IOException {
    Files.createDirectories(jar.getParent());
    return Files.writeString(jar, "jar").toString();
  }

  private File launch(final List<String> classPath) throws IOException {
    final var data =
        new TestLaunchData(
            "junit-platform",
            LaunchMode.CLASSPATH,
            "/jdk",
            "",
            List.of(),
            classPath,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");
    final Path launch = tmp.resolve("test-launch.json");
    Files.writeString(launch, Json.toJson(data), StandardCharsets.UTF_8);
    return launch.toFile();
  }
}
