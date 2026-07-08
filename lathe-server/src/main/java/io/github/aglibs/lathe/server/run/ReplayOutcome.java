package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

public record ReplayOutcome(boolean launched, List<String> blockedReasons, int exitCode) {

  public ReplayOutcome {
    ValidCheck.check().notNull(blockedReasons, "blockedReasons").validate();
    blockedReasons = List.copyOf(blockedReasons);
  }

  public static ReplayOutcome blocked(final List<String> reasons) {
    return new ReplayOutcome(false, reasons, -1);
  }

  public static ReplayOutcome completed(final int exitCode) {
    return new ReplayOutcome(true, List.of(), exitCode);
  }
}
