package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// notes: what an open launch runs from the last build rather than from .lathe/, so its result may
// not reflect the latest edits.
public record CompletenessResult(boolean complete, List<String> reasons, List<String> notes) {

  public CompletenessResult {
    ValidCheck.check().notNull(reasons, "reasons").notNull(notes, "notes").validate();
    // Precompute into a primitive: reasons is reassigned below, so a lambda can't close over the
    // parameter itself without failing the effectively-final check.
    final boolean reasonsEmpty = reasons.isEmpty();
    ValidCheck.check()
        .when(complete, v -> v.assertTrue(reasonsEmpty, "reasons"))
        .when(!complete, v -> v.assertFalse(reasonsEmpty, "reasons"))
        .validate();
    reasons = List.copyOf(reasons);
    notes = List.copyOf(notes);
  }

  public static CompletenessResult open(final List<String> notes) {
    return new CompletenessResult(true, List.of(), notes);
  }

  public static CompletenessResult blocked(final List<String> reasons) {
    return new CompletenessResult(false, reasons, List.of());
  }
}
