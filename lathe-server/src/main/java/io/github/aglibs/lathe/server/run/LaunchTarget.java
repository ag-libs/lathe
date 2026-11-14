package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.stream.Collectors;

// What a run or a debug launches: a module's tests or one of its main classes. A run and a debug
// share every step but the replay they prepare for it.
public sealed interface LaunchTarget {

  // The module, reactor-relative ("" for the root).
  String module();

  // How logs and the client name the launch: the selected tests, or the main class.
  String label();

  RunKind kind();

  static LaunchTarget of(final RunItem config) {
    return config.kind() == RunKind.MAIN
        ? new Main(config.module(), config.mainClass())
        : new Tests(config.module(), config.selectors());
  }

  record Tests(String module, List<TestSelection> selections) implements LaunchTarget {

    public Tests {
      ValidCheck.check().notNull(module, "module").notNull(selections, "selections").validate();
      selections = List.copyOf(selections);
    }

    @Override
    public String label() {
      return selections.stream().map(TestSelection::value).collect(Collectors.joining(", "));
    }

    @Override
    public RunKind kind() {
      return RunKind.TEST;
    }
  }

  record Main(String module, String mainClass) implements LaunchTarget {

    public Main {
      ValidCheck.check().notNull(module, "module").notBlank(mainClass, "mainClass").validate();
    }

    @Override
    public String label() {
      return mainClass;
    }

    @Override
    public RunKind kind() {
      return RunKind.MAIN;
    }
  }
}
