package io.github.aglibs.lathe.runner;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.discovery.ClassNameFilter;
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
  // Class-name regexes narrowing a package or module run to what the build's own test run executes.
  static final String INCLUDE_CLASS_NAME = "--include-classname";
  static final String EXCLUDE_CLASS_NAME = "--exclude-classname";

  /** What to run: the selectors, and the class-name filters narrowing them. */
  record Request(List<DiscoverySelector> selectors, List<Filter<?>> filters) {

    Request {
      selectors = List.copyOf(selectors);
      filters = List.copyOf(filters);
    }
  }

  private TestSelectorParser() {}

  static Request parse(final String[] args) {
    final var selectors = new ArrayList<DiscoverySelector>();
    final var includes = new ArrayList<String>();
    final var excludes = new ArrayList<String>();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException("Missing value for selector %s".formatted(args[i]));
      }

      switch (args[i]) {
        case INCLUDE_CLASS_NAME -> includes.add(args[i + 1]);
        case EXCLUDE_CLASS_NAME -> excludes.add(args[i + 1]);
        default -> selectors.addAll(selectors(args[i], args[i + 1]));
      }
    }

    if (selectors.isEmpty()) {
      throw new IllegalArgumentException("No test selectors provided");
    }

    final var filters = new ArrayList<Filter<?>>();
    if (!includes.isEmpty()) {
      filters.add(ClassNameFilter.includeClassNamePatterns(includes.toArray(String[]::new)));
    }

    if (!excludes.isEmpty()) {
      filters.add(ClassNameFilter.excludeClassNamePatterns(excludes.toArray(String[]::new)));
    }

    return new Request(selectors, filters);
  }

  private static List<DiscoverySelector> selectors(final String flag, final String value) {
    if (value.isBlank()) {
      throw new IllegalArgumentException("Blank value for selector %s".formatted(flag));
    }

    return switch (flag) {
      case SELECT_CLASS -> classAndStaticNested(value);
      case SELECT_METHOD -> List.of(DiscoverySelectors.selectMethod(value));
      case SELECT_PACKAGE -> List.of(DiscoverySelectors.selectPackage(value));
      case SELECT_MODULE -> List.of(DiscoverySelectors.selectModule(value));
      default -> throw new IllegalArgumentException("Unknown selector %s".formatted(flag));
    };
  }

  // JUnit's class selector reaches @Nested inner classes but not static nested ones, which editors
  // show under the outer class, so those are selected too. Loading runs no static initializer; a
  // class that can't be loaded keeps just its own selector, and JUnit reports it as before.
  private static List<DiscoverySelector> classAndStaticNested(final String className) {
    final var selectors = new ArrayList<DiscoverySelector>();
    selectors.add(DiscoverySelectors.selectClass(className));
    final var pending = new ArrayDeque<Class<?>>();
    try {
      pending.add(Class.forName(className, false, Thread.currentThread().getContextClassLoader()));
      while (!pending.isEmpty()) {
        for (final Class<?> nested : staticNested(pending.poll())) {
          selectors.add(DiscoverySelectors.selectClass(nested));
          pending.add(nested);
        }
      }
    } catch (final ClassNotFoundException | LinkageError e) {
      // Nothing more to add: the selectors found so far still run.
    }

    return List.copyOf(selectors);
  }

  private static List<Class<?>> staticNested(final Class<?> type) {
    return Arrays.stream(type.getDeclaredClasses())
        .filter(nested -> Modifier.isStatic(nested.getModifiers()))
        .toList();
  }
}
