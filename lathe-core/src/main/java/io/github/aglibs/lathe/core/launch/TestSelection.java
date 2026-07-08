package io.github.aglibs.lathe.core.launch;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

public record TestSelection(TestSelectionKind kind, String value) {

  public TestSelection {
    ValidCheck.check().notNull(kind, "kind").notBlank(value, "value").validate();
  }

  public List<String> toRunnerArgs() {
    return List.of(kind.runnerFlag(), value);
  }
}
