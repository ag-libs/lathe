package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RunItemTest {

  @Test
  void constructor_nullKind_throws() {
    assertThatThrownBy(
            () -> new RunItem(null, "app", null, null, null, null, null, null, null, null, null))
        .hasMessageContaining("kind");
  }

  @Test
  void constructor_mainClassWithTestKind_throws() {
    assertThatThrownBy(
            () ->
                new RunItem(
                    "dev",
                    "app",
                    RunKind.TEST,
                    "com.example.App",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .hasMessageContaining("mainClass");
  }

  @Test
  void constructor_selectorsWithMainKind_throws() {
    final var selectors = List.of(new TestSelection(TestSelectionKind.CLASS, "com.example.Foo"));
    assertThatThrownBy(
            () ->
                new RunItem(
                    "dev",
                    "app",
                    RunKind.MAIN,
                    null,
                    selectors,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .hasMessageContaining("selectors");
  }

  @Test
  void empty_omittedCollections_defaultToEmpty() {
    final var item = RunItem.empty("app", RunKind.MAIN);

    assertThat(item.name()).isNull();
    assertThat(item.hasTarget()).isFalse();
    assertThat(item.args()).isEmpty();
    assertThat(item.jvmArgs()).isEmpty();
    assertThat(item.env()).isEmpty();
    assertThat(item.selectors()).isEmpty();
    assertThat(item.classpathAppend()).isEmpty();
    assertThat(item.cwd()).isNull();
  }

  @Test
  void configLabel_namedAndBaseline_reflectOverlayPresence() {
    final var named =
        new RunItem(
            "dev",
            "app",
            RunKind.MAIN,
            "com.example.App",
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    final var bareBaseline = RunItem.empty("app", RunKind.TEST);
    final var richBaseline =
        new RunItem(
            null, "app", RunKind.TEST, null, null, null, List.of("-Dx=1"), null, null, null, null);

    assertThat(named.configLabel()).isEqualTo("dev");
    assertThat(bareBaseline.configLabel()).isEqualTo("default");
    assertThat(richBaseline.configLabel()).isEqualTo("default+baseline");
  }

  @Test
  void mergedWith_localLayer_takesLocalIdentityConcatsListsUnionsEnv() {
    final var shared =
        new RunItem(
            null,
            "app",
            RunKind.MAIN,
            null,
            null,
            List.of("a"),
            List.of("-Dx=1"),
            Map.of("A", "1", "B", "1"),
            "shared-dir",
            List.of("/cp/shared"),
            null);
    final var local =
        new RunItem(
            "dev",
            "app",
            RunKind.MAIN,
            "com.example.App",
            null,
            List.of("b"),
            List.of("-Dx=2"),
            Map.of("B", "2", "C", "3"),
            "local-dir",
            List.of("/cp/local"),
            null);

    final RunItem merged = shared.mergedWith(local);

    assertThat(merged.name()).isEqualTo("dev");
    assertThat(merged.mainClass()).isEqualTo("com.example.App");
    assertThat(merged.cwd()).isEqualTo("local-dir");
    assertThat(merged.args()).containsExactly("a", "b");
    assertThat(merged.jvmArgs()).containsExactly("-Dx=1", "-Dx=2");
    assertThat(merged.classpathAppend()).containsExactly("/cp/shared", "/cp/local");
    assertThat(merged.env()).containsOnly(entry("A", "1"), entry("B", "2"), entry("C", "3"));
  }
}
