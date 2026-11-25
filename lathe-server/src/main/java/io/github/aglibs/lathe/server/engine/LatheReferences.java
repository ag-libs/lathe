package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * A references result: the true total, whether the list was capped, the enriched references, how
 * many uses an access filter left out, so an empty answer can say the symbol has uses, and whether
 * the position named a symbol at all, so an unused symbol is not taken for no symbol. An unused
 * field or accessor carries where its property name is still text.
 */
public record LatheReferences(
    int total,
    boolean truncated,
    List<LatheReference> references,
    int filteredOut,
    boolean resolved,
    LathePropertyMentions propertyMentions) {

  public LatheReferences {
    ValidCheck.check()
        .notNull(references, "references")
        .isNonNegative(filteredOut, "filteredOut")
        .notNull(propertyMentions, "propertyMentions")
        .validate();
    references = List.copyOf(references);
  }

  public LatheReferences(
      final int total,
      final boolean truncated,
      final List<LatheReference> references,
      final int filteredOut,
      final boolean resolved) {
    this(total, truncated, references, filteredOut, resolved, LathePropertyMentions.NONE);
  }

  public LatheReferences withPropertyMentions(final LathePropertyMentions mentions) {
    return new LatheReferences(total, truncated, references, filteredOut, resolved, mentions);
  }
}
