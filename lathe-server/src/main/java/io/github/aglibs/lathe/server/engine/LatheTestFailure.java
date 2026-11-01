package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// One failed test: its Class#method id, the exception summary, the line (-1 if unknown), and the
// last lines it printed, which often explain the failure.
public record LatheTestFailure(String test, String summary, int line, List<String> output) {

  public LatheTestFailure {
    ValidCheck.check()
        .notNull(test, "test")
        .notNull(summary, "summary")
        .notNull(output, "output")
        .validate();
    output = List.copyOf(output);
  }
}
