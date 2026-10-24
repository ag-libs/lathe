package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheSyncTaskFunctionalTest {

  private static final String SETTINGS =
      """
      plugins { id("io.github.ag-libs.lathe") }
      rootProject.name = "root"
      include("lib", "app")
      """
          + GradleFixture.latheRepositories();

  @TempDir Path dir;

  // A library and an app using it: latheSync writes the workspace files from both projects' facts,
  // installs the server, and is configuration-cache safe.
  @Test
  void sync_multiProjectBuild_writesTheWorkspaceFilesAndInstallsTheServer() throws Exception {
    final var fixture = multiProject();

    fixture.runner("latheSync", GradleFixture.latheCache(), "--configuration-cache").build();
    final var second =
        fixture.runner("latheSync", GradleFixture.latheCache(), "--configuration-cache").build();

    assertThat(second.getOutput()).contains("Configuration cache entry reused.");
    final var lathe = dir.resolve(LatheLayout.LATHE_DIR);
    final WorkspaceManifestData workspace =
        Json.read(lathe.resolve(LatheLayout.WORKSPACE_JSON), WorkspaceManifestData.class);
    assertThat(workspace.outputs())
        .contains(
            new OutputData("lib/build/classes/java/main", "lib", LatheLayout.CLASSES_DIR),
            new OutputData("lib/build/libs/lib.jar", "lib", LatheLayout.CLASSES_DIR),
            new OutputData("app/build/classes/java/test", "app", LatheLayout.TEST_CLASSES_DIR));
    assertThat(workspace.resourceRoots())
        .contains(
            new ResourceRootData(
                "lib/src/main/resources", "lib/build/resources/main", "", false, "lib"));
    assertThat(workspace.buildFiles())
        .extracting(BuildFileData::path)
        .contains("settings.gradle.kts", "lib/build.gradle.kts", "app/build.gradle.kts");
    assertThat(Path.of(Files.readString(lathe.resolve(LatheLayout.JAVA_HOME_FILE)))).isDirectory();
    assertThat(lathe.resolve(LatheLayout.LAUNCHER_SCRIPT)).exists();
    assertThat(Files.readString(lathe.resolve(LatheLayout.SYNC_SCRIPT), StandardCharsets.UTF_8))
        .contains("latheSync", "--module");
    assertThat(lathe.resolve("app/classes/com/example/app/App.class")).exists();
  }

  // As Maven's -pl: only that module's captures, and the workspace files are left alone.
  @Test
  void sync_oneModule_capturesItAloneAndWritesNoWorkspaceFile() throws Exception {
    final var fixture = multiProject();

    final var result =
        fixture.runner("latheSync", "--module", "lib", GradleFixture.latheCache()).build();

    assertThat(result.task(":lib:latheCaptureMain")).isNotNull();
    assertThat(result.task(":app:compileJava")).isNull();
    assertThat(dir.resolve(".lathe/lib/classes/com/example/lib/Lib.class")).exists();
    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON))
        .doesNotExist();
  }

  // Module-access flags a forked compile and the daemon run with reach the server's jvm.args.
  @Test
  void sync_forkedCompileWithAccessFlags_writesThemToJvmArgs() throws Exception {
    final var fixture =
        multiProject()
            .file(
                "app/build.gradle.kts",
                """
                plugins { java }
                dependencies { implementation(project(":lib")) }
                tasks.compileJava {
                  options.isFork = true
                  options.forkOptions.jvmArgs = listOf("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
                }
                """)
            .file(
                "gradle.properties",
                "org.gradle.jvmargs=-Xmx512m --add-opens=java.base/java.lang=ALL-UNNAMED\n");

    fixture.runner("latheSync", GradleFixture.latheCache()).build();

    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.JVM_ARGS_FILE))
        .content()
        .contains(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED")
        .doesNotContain("-Xmx512m");
  }

  private GradleFixture multiProject() throws Exception {
    return new GradleFixture(dir)
        .file("settings.gradle.kts", SETTINGS)
        .file("build.gradle.kts", "")
        .file("lib/build.gradle.kts", "plugins { `java-library` }\n")
        .file(
            "lib/src/main/java/com/example/lib/Lib.java",
            "package com.example.lib; public class Lib {}\n")
        .file("lib/src/main/resources/lib.properties", "name=lib\n")
        .file(
            "app/build.gradle.kts",
            "plugins { java }\ndependencies { implementation(project(\":lib\")) }\n")
        .file(
            "app/src/main/java/com/example/app/App.java",
            "package com.example.app; class App { com.example.lib.Lib lib; }\n")
        .file(
            "app/src/test/java/com/example/app/AppTest.java",
            "package com.example.app; class AppTest {}\n");
  }
}
