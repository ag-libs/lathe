package io.github.aglibs.lathe.runner;

import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;

final class TestSelectorParser {

  /**
   * Selector flags. Mirror {@code TestSelectionKind}'s {@code runnerFlag()} values in {@code
   * lathe-core} (the server emits them via {@code TestSelection.toRunnerArgs()}) by value only: the
   * runner must not depend on {@code lathe-core}. A drift-guard test pins these equal.
   */
  static final String SELECT_CLASS = "--select-class";

  static final String SELECT_METHOD = "--select-method";
  static final String SELECT_PACKAGE = "--select-package";
  static final String SELECT_MODULE = "--select-module";

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

    return switch (flag) {
      case SELECT_CLASS -> DiscoverySelectors.selectClass(value);
      case SELECT_METHOD -> DiscoverySelectors.selectMethod(value);
      case SELECT_PACKAGE -> DiscoverySelectors.selectPackage(value);
      case SELECT_MODULE -> DiscoverySelectors.selectModule(value);
      default -> throw new IllegalArgumentException("Unknown selector %s".formatted(flag));
    };
  }
}
