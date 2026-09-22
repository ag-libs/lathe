package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.RunKind;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RunOverlaySetTest {

  @Test
  void defaultFor_namedConfigMatchingModuleKind_isNotAutoApplied() {
    final var named =
        new RunItem(
            "dev",
            "app",
            RunKind.MAIN,
            "com.example.App",
            null,
            null,
            List.of("-Ddev=1"),
            null,
            null,
            null,
            null);
    final var set = new RunOverlaySet(List.of(named));

    assertThat(set.defaultFor("app", RunKind.MAIN)).isEqualTo(RunItem.empty("app", RunKind.MAIN));
  }

  @Test
  void byName_unknownName_isEmpty() {
    final var set = new RunOverlaySet(List.of());

    assertThat(set.byName("nope")).isEmpty();
  }

  @Test
  void configs_returnsOnlyNamedEntries() {
    final var baseline = RunItem.empty("app", RunKind.TEST);
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
    final var set = new RunOverlaySet(List.of(baseline, named));

    assertThat(set.configs()).singleElement().extracting(RunItem::name).isEqualTo("dev");
  }
}
