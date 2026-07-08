package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

public record CompletenessResult(boolean complete, List<String> reasons) {

  public CompletenessResult {
    ValidCheck.check().notNull(reasons, "reasons").validate();
    // Precompute into a primitive: reasons is reassigned below, so a lambda can't close over the
    // parameter itself without failing the effectively-final check.
    final boolean reasonsEmpty = reasons.isEmpty();
    ValidCheck.check()
        .when(complete, v -> v.assertTrue(reasonsEmpty, "reasons"))
        .when(!complete, v -> v.assertFalse(reasonsEmpty, "reasons"))
        .validate();
    reasons = List.copyOf(reasons);
  }

  public static CompletenessResult open() {
    return new CompletenessResult(true, List.of());
  }

  public static CompletenessResult blocked(final List<String> reasons) {
    return new CompletenessResult(false, reasons);
  }
}
