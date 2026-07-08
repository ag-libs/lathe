package io.github.aglibs.lathe.runner;

import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;

final class TestSelectorParser {

  private TestSelectorParser() {}

  static List<DiscoverySelector> parse(final String[] args) {
    if (args.length == 0) {
      throw new IllegalArgumentException("No test selectors provided");
    }

    final var selectors = new ArrayList<DiscoverySelector>();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException("Missing value for selector %s".formatted(args[i]));
      }

      selectors.add(selector(args[i], args[i + 1]));
    }

    return List.copyOf(selectors);
  }

  private static DiscoverySelector selector(final String flag, final String value) {
    if (value.isBlank()) {
      throw new IllegalArgumentException("Blank value for selector %s".formatted(flag));
    }

    return switch (TestSelectionKind.fromRunnerFlag(flag)) {
      case CLASS -> DiscoverySelectors.selectClass(value);
      case METHOD -> DiscoverySelectors.selectMethod(value);
      case PACKAGE -> DiscoverySelectors.selectPackage(value);
      case MODULE -> DiscoverySelectors.selectModule(value);
    };
  }
}
