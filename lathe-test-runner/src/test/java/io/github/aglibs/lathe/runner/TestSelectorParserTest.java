package io.github.aglibs.lathe.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.MethodSelector;
import org.junit.platform.engine.discovery.ModuleSelector;
import org.junit.platform.engine.discovery.PackageSelector;

final class TestSelectorParserTest {

  @Test
  void parse_classSelector_returnsClassSelector() {
    assertSelector(TestSelectionKind.CLASS, "com.example.T", ClassSelector.class);
  }

  @Test
  void parse_methodSelector_returnsMethodSelector() {
    assertSelector(TestSelectionKind.METHOD, "com.example.T#m", MethodSelector.class);
  }

  @Test
  void parse_packageSelector_returnsPackageSelector() {
    assertSelector(TestSelectionKind.PACKAGE, "com.example", PackageSelector.class);
  }

  @Test
  void parse_moduleSelector_returnsModuleSelector() {
    assertSelector(TestSelectionKind.MODULE, "com.example.module", ModuleSelector.class);
  }

  @Test
  void parse_missingValue_throwsIllegalArgumentException() {
    assertThatThrownBy(
            () -> TestSelectorParser.parse(new String[] {TestSelectionKind.CLASS.runnerFlag()}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing value for selector --select-class");
  }

  @Test
  void parse_unknownSelector_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> TestSelectorParser.parse(new String[] {"--select-unknown", "x"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown selector --select-unknown");
  }

  private static void assertSelector(
      final TestSelectionKind kind,
      final String value,
      final Class<? extends DiscoverySelector> selectorType) {
    final var selectors = TestSelectorParser.parse(new String[] {kind.runnerFlag(), value});

    assertThat(selectors).singleElement().isInstanceOf(selectorType);
  }
}
