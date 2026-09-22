package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunConfigWriterTest {

  @TempDir private Path workspaceRoot;

  @Test
  void save_mainWithoutName_derivesSimpleClassNameAndWritesLocalFile() throws IOException {
    final RunConfigWriter.Saved saved =
        new RunConfigWriter(workspaceRoot)
            .save(
                null, "services/app", RunKind.MAIN, "com.example.app.AppServer", List.of(), false);

    assertThat(saved.name()).isEqualTo("AppServer");
    assertThat(saved.path())
        .isEqualTo(
            workspaceRoot
                .resolve(LatheLayout.LATHE_DIR)
                .resolve(LatheLayout.RUN_CONFIG_LOCAL_FILE)
                .toString());

    final RunItem config =
        new RunConfigReader(workspaceRoot).read().byName("AppServer").orElseThrow();
    assertThat(config.kind()).isEqualTo(RunKind.MAIN);
    assertThat(config.module()).isEqualTo("services/app");
    assertThat(config.mainClass()).isEqualTo("com.example.app.AppServer");
  }

  @Test
  void save_testMethodSelector_derivesClassDotMethodName() throws IOException {
    final var selectors =
        List.of(new TestSelection(TestSelectionKind.METHOD, "com.example.SmokeTest#loginWorks"));

    final RunConfigWriter.Saved saved =
        new RunConfigWriter(workspaceRoot).save(null, "app", RunKind.TEST, null, selectors, false);

    assertThat(saved.name()).isEqualTo("SmokeTest.loginWorks");
  }

  @Test
  void save_existingNameWithoutOverwrite_throwsAndPreservesExisting() throws IOException {
    final var writer = new RunConfigWriter(workspaceRoot);
    writer.save("dev", "app", RunKind.MAIN, "com.example.First", List.of(), false);

    assertThatThrownBy(
            () -> writer.save("dev", "app", RunKind.MAIN, "com.example.Second", List.of(), false))
        .hasMessageContaining("already exists");

    final RunItem config = new RunConfigReader(workspaceRoot).read().byName("dev").orElseThrow();
    assertThat(config.mainClass()).isEqualTo("com.example.First");
  }

  @Test
  void save_existingNameWithOverwrite_replacesAndPreservesSiblingsAndDefaults() throws IOException {
    Files.createDirectories(workspaceRoot.resolve(LatheLayout.LATHE_DIR));
    Files.writeString(
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.RUN_CONFIG_LOCAL_FILE),
        """
        { "defaults": [ { "kind": "TEST", "jvmArgs": ["-Duser.timezone=UTC"] } ],
          "configs": { "keep": { "kind": "MAIN", "module": "app", "mainClass": "com.example.Keep" },
            "dev": { "kind": "MAIN", "module": "app", "mainClass": "com.example.Old" } } }
        """);

    new RunConfigWriter(workspaceRoot)
        .save("dev", "app", RunKind.MAIN, "com.example.New", List.of(), true);

    final RunOverlaySet set = new RunConfigReader(workspaceRoot).read();
    assertThat(set.byName("dev").orElseThrow().mainClass()).isEqualTo("com.example.New");
    assertThat(set.byName("keep").orElseThrow().mainClass()).isEqualTo("com.example.Keep");
    assertThat(set.defaultFor("app", RunKind.TEST).jvmArgs())
        .containsExactly("-Duser.timezone=UTC");
  }
}
