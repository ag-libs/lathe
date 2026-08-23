package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.core.schema.RunKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RunItemTest {

  @Test
  void constructor_nullKind_throws() {
    assertThatThrownBy(() -> new RunItem("app", null, null, null, null, null, null, null))
        .hasMessageContaining("kind");
  }

  @Test
  void constructor_nullModule_allowedAsWorkspaceEntry() {
    final var item =
        new RunItem(null, RunKind.TEST, null, List.of("-Dprofile=dev"), null, null, null, null);

    assertThat(item.module()).isNull();
    assertThat(item.jvmArgs()).containsExactly("-Dprofile=dev");
  }

  @Test
  void constructor_omittedCollections_defaultToEmpty() {
    final RunItem item = RunItem.empty("app", RunKind.MAIN);

    assertThat(item.args()).isEmpty();
    assertThat(item.jvmArgs()).isEmpty();
    assertThat(item.env()).isEmpty();
    assertThat(item.classpathAppend()).isEmpty();
    assertThat(item.modulePathAppend()).isEmpty();
    assertThat(item.cwd()).isNull();
  }

  @Test
  void mergedWith_localLayer_overridesCwdConcatsListsAndUnionsEnv() {
    final var shared =
        new RunItem(
            "app",
            RunKind.MAIN,
            List.of("a"),
            List.of("-Dx=1"),
            Map.of("A", "1", "B", "1"),
            "shared-dir",
            List.of("/cp/shared"),
            List.of());
    final var local =
        new RunItem(
            "app",
            RunKind.MAIN,
            List.of("b"),
            List.of("-Dx=2"),
            Map.of("B", "2", "C", "3"),
            "local-dir",
            List.of("/cp/local"),
            List.of());

    final RunItem merged = shared.mergedWith(local);

    assertThat(merged.cwd()).isEqualTo("local-dir");
    assertThat(merged.args()).containsExactly("a", "b");
    assertThat(merged.jvmArgs()).containsExactly("-Dx=1", "-Dx=2");
    assertThat(merged.classpathAppend()).containsExactly("/cp/shared", "/cp/local");
    assertThat(merged.env()).containsOnly(entry("A", "1"), entry("B", "2"), entry("C", "3"));
  }
}
