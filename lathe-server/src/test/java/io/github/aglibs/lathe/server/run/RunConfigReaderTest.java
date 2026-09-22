package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunConfigReaderTest {

  @TempDir private Path workspaceRoot;

  @Test
  void read_noFiles_returnsBuiltInDefault() {
    final RunItem item = new RunConfigReader(workspaceRoot).read().defaultFor("app", RunKind.MAIN);

    assertThat(item).isEqualTo(RunItem.empty("app", RunKind.MAIN));
  }

  @Test
  void read_workspaceBaseline_appliesToAnyModule() throws IOException {
    writeShared(
        """
        { "defaults": [ { "kind": "TEST", "jvmArgs": ["-Dprofile=dev"] } ] }
        """);

    final RunItem item =
        new RunConfigReader(workspaceRoot).read().defaultFor("anything", RunKind.TEST);

    assertThat(item.jvmArgs()).containsExactly("-Dprofile=dev");
  }

  @Test
  void read_moduleBaseline_winsOverWorkspaceBaseline() throws IOException {
    writeShared(
        """
        { "defaults": [
          { "kind": "TEST", "jvmArgs": ["-Dglobal=1"] },
          { "kind": "TEST", "module": "app", "jvmArgs": ["-Dapp=1"] } ] }
        """);

    final RunOverlaySet set = new RunConfigReader(workspaceRoot).read();

    assertThat(set.defaultFor("app", RunKind.TEST).jvmArgs()).containsExactly("-Dapp=1");
    assertThat(set.defaultFor("other", RunKind.TEST).jvmArgs()).containsExactly("-Dglobal=1");
  }

  @Test
  void read_bothLayers_localBaselineWinsWithFieldMerge() throws IOException {
    writeShared(
        """
        { "defaults": [ { "kind": "MAIN", "module": "app",
          "jvmArgs": ["-Dprofile=prod"], "env": { "A": "1" } } ] }
        """);
    writeLocal(
        """
        { "defaults": [ { "kind": "MAIN", "module": "app",
          "jvmArgs": ["-Dagent=x"], "env": { "A": "2", "B": "3" }, "cwd": "run-dir" } ] }
        """);

    final RunItem item = new RunConfigReader(workspaceRoot).read().defaultFor("app", RunKind.MAIN);

    assertThat(item.jvmArgs()).containsExactly("-Dprofile=prod", "-Dagent=x");
    assertThat(item.env()).containsOnly(entry("A", "2"), entry("B", "3"));
    assertThat(item.cwd()).isEqualTo("run-dir");
  }

  @Test
  void read_namedConfig_composesOverMatchingBaseline() throws IOException {
    writeShared(
        """
        { "defaults": [ { "kind": "MAIN", "jvmArgs": ["-Dbase=1"] } ],
          "configs": { "dev": { "kind": "MAIN", "module": "app",
            "mainClass": "com.example.App", "jvmArgs": ["-Ddev=1"] } } }
        """);

    final RunItem config = new RunConfigReader(workspaceRoot).read().byName("dev").orElseThrow();

    assertThat(config.name()).isEqualTo("dev");
    assertThat(config.mainClass()).isEqualTo("com.example.App");
    assertThat(config.jvmArgs()).containsExactly("-Dbase=1", "-Ddev=1");
  }

  @Test
  void read_configLayers_localBodyWinsByName() throws IOException {
    writeShared(
        """
        { "configs": { "dev": { "kind": "MAIN", "module": "app",
          "mainClass": "com.example.App", "jvmArgs": ["-Dshared=1"] } } }
        """);
    writeLocal(
        """
        { "configs": { "dev": { "kind": "MAIN", "module": "app",
          "mainClass": "com.example.App", "jvmArgs": ["-Dlocal=1"], "cwd": "here" } } }
        """);

    final RunItem config = new RunConfigReader(workspaceRoot).read().byName("dev").orElseThrow();

    assertThat(config.jvmArgs()).containsExactly("-Dshared=1", "-Dlocal=1");
    assertThat(config.cwd()).isEqualTo("here");
  }

  @Test
  void read_namedTestConfig_parsesSelectors() throws IOException {
    writeShared(
        """
        { "configs": { "smoke": { "kind": "TEST", "module": "app",
          "selectors": [ { "selectorKind": "CLASS", "selectorValue": "com.example.SmokeTest" } ] } } }
        """);

    final RunItem config = new RunConfigReader(workspaceRoot).read().byName("smoke").orElseThrow();

    assertThat(config.selectors()).hasSize(1);
    assertThat(config.selectors().getFirst().kind()).isEqualTo(TestSelectionKind.CLASS);
    assertThat(config.selectors().getFirst().value()).isEqualTo("com.example.SmokeTest");
  }

  @Test
  void read_baselineWithTarget_isIgnored() throws IOException {
    writeShared(
        """
        { "defaults": [ { "kind": "MAIN", "mainClass": "com.example.App" } ] }
        """);

    final RunItem item = new RunConfigReader(workspaceRoot).read().defaultFor("app", RunKind.MAIN);

    assertThat(item).isEqualTo(RunItem.empty("app", RunKind.MAIN));
  }

  @Test
  void read_configWithoutTarget_isIgnored() throws IOException {
    writeShared(
        """
        { "configs": { "dev": { "kind": "MAIN", "module": "app", "jvmArgs": ["-Dx=1"] } } }
        """);

    assertThat(new RunConfigReader(workspaceRoot).read().byName("dev")).isEmpty();
  }

  private void writeShared(final String json) throws IOException {
    Files.writeString(workspaceRoot.resolve(LatheLayout.RUN_CONFIG_SHARED_FILE), json);
  }

  private void writeLocal(final String json) throws IOException {
    final Path dir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(LatheLayout.RUN_CONFIG_LOCAL_FILE), json);
  }
}
