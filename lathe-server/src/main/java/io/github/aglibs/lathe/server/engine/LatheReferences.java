package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * A references result: the true total, whether the list was capped, the enriched references, and
 * how many uses an access filter left out, so an empty answer can say the symbol has uses.
 */
public record LatheReferences(
    int total, boolean truncated, List<LatheReference> references, int filteredOut) {

  public LatheReferences {
    ValidCheck.check()
        .notNull(references, "references")
        .isNonNegative(filteredOut, "filteredOut")
        .validate();
    references = List.copyOf(references);
  }
}
