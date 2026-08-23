package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
  void read_workspaceEntry_appliesToAnyModule() throws IOException {
    writeShared(List.of(item(null, List.of("-Dprofile=dev"))));

    final RunItem item =
        new RunConfigReader(workspaceRoot).read().defaultFor("anything", RunKind.TEST);

    assertThat(item.jvmArgs()).containsExactly("-Dprofile=dev");
  }

  @Test
  void read_moduleEntry_winsOverWorkspaceEntry() throws IOException {
    writeShared(List.of(item(null, List.of("-Dglobal=1")), item("app", List.of("-Dapp=1"))));

    final RunOverlaySet set = new RunConfigReader(workspaceRoot).read();

    assertThat(set.defaultFor("app", RunKind.TEST).jvmArgs()).containsExactly("-Dapp=1");
    assertThat(set.defaultFor("other", RunKind.TEST).jvmArgs()).containsExactly("-Dglobal=1");
  }

  @Test
  void read_bothLayers_localWinsWithFieldMerge() throws IOException {
    writeShared(
        List.of(
            new RunItem(
                "app",
                RunKind.MAIN,
                null,
                List.of("-Dprofile=prod"),
                Map.of("A", "1"),
                null,
                null,
                null)));
    writeLocal(
        List.of(
            new RunItem(
                "app",
                RunKind.MAIN,
                null,
                List.of("-Dagent=x"),
                Map.of("A", "2", "B", "3"),
                "run-dir",
                null,
                null)));

    final RunItem item = new RunConfigReader(workspaceRoot).read().defaultFor("app", RunKind.MAIN);

    assertThat(item.jvmArgs()).containsExactly("-Dprofile=prod", "-Dagent=x");
    assertThat(item.env()).containsOnly(entry("A", "2"), entry("B", "3"));
    assertThat(item.cwd()).isEqualTo("run-dir");
  }

  @Test
  void read_kindInJson_parsesByEnumName() throws IOException {
    Files.writeString(
        workspaceRoot.resolve(LatheLayout.RUN_CONFIG_SHARED_FILE),
        "[{\"module\":\"app\",\"kind\":\"MAIN\",\"jvmArgs\":[\"-Dk=v\"]}]");

    final RunItem item = new RunConfigReader(workspaceRoot).read().defaultFor("app", RunKind.MAIN);

    assertThat(item.jvmArgs()).containsExactly("-Dk=v");
  }

  private static RunItem item(final String module, final List<String> jvmArgs) {
    return new RunItem(module, RunKind.TEST, null, jvmArgs, null, null, null, null);
  }

  private void writeShared(final List<RunItem> entries) throws IOException {
    Json.write(entries, workspaceRoot.resolve(LatheLayout.RUN_CONFIG_SHARED_FILE));
  }

  private void writeLocal(final List<RunItem> entries) throws IOException {
    Json.write(
        entries,
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.RUN_CONFIG_LOCAL_FILE));
  }
}
