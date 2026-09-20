package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * A references result: the true total, whether the list was capped, and the enriched references.
 */
public record LatheReferences(int total, boolean truncated, List<LatheLocation> references) {

  public LatheReferences {
    ValidCheck.check().notNull(references, "references").validate();
    references = List.copyOf(references);
  }
}
