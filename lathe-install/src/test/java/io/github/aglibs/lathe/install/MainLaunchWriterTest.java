package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainLaunchWriterTest {

  @TempDir Path workspaceRoot;

  // Run from the module's own directory; nothing beyond the paths for a classpath launch.
  @Test
  void writeClasspath_writesAClasspathLaunchRunFromTheModuleDir_onlyWhenChanged() throws Exception {
    MainLaunchWriter.writeClasspath(
        workspaceRoot, "app", "/jdk", List.of("app/target/classes", "/repo/dep.jar"));
    final Path file = launchFile("app");
    final var stamp = FileTime.fromMillis(1_000_000L);
    Files.setLastModifiedTime(file, stamp);

    MainLaunchWriter.writeClasspath(
        workspaceRoot, "app", "/jdk", List.of("app/target/classes", "/repo/dep.jar"));

    assertThat(Files.getLastModifiedTime(file)).isEqualTo(stamp);
    final MainLaunchData data = Json.read(file, MainLaunchData.class);
    assertThat(data.mode()).isEqualTo(LaunchMode.CLASSPATH);
    assertThat(data.javaHome()).isEqualTo("/jdk");
    assertThat(data.mainModule()).isNull();
    assertThat(data.modulePath()).isEmpty();
    assertThat(data.classPath()).containsExactly("app/target/classes", "/repo/dep.jar");
    assertThat(data.workingDir()).isEqualTo("app");
  }

  @Test
  void writeModule_writesAModuleLaunchWithItsMainModuleAndBothPaths() throws Exception {
    MainLaunchWriter.writeModule(
        workspaceRoot,
        "jpms",
        "/jdk",
        "com.example.jpms",
        List.of("jpms/target/classes", "/repo/mod.jar"),
        List.of("/repo/plain.jar"));

    final MainLaunchData data = Json.read(launchFile("jpms"), MainLaunchData.class);
    assertThat(data.mode()).isEqualTo(LaunchMode.MODULE);
    assertThat(data.mainModule()).isEqualTo("com.example.jpms");
    assertThat(data.modulePath()).containsExactly("jpms/target/classes", "/repo/mod.jar");
    assertThat(data.classPath()).containsExactly("/repo/plain.jar");
    assertThat(data.workingDir()).isEqualTo("jpms");
  }

  private Path launchFile(final String moduleRel) {
    return workspaceRoot
        .resolve(LatheLayout.LATHE_DIR)
        .resolve(moduleRel)
        .resolve(LatheLayout.MAIN_LAUNCH_FILE);
  }
}
