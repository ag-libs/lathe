package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * The pre-edit impact of a symbol: its rendered signature, whether it is a method, its family (for
 * a method, the methods it overrides by {@code Type#name(parameters)}, those that override it, and
 * the abstract ones that redeclare it; for a type, its subtypes in {@code overriddenBy}), how many
 * production vs test references it has, the reactor modules that use or implement it, and the
 * relevant test classes. Answers "what will change if I touch this?" before an edit, javac-accurate
 * and cross-module. Reference counts reflect the (capped) reference set — {@code
 * referencesTruncated} is true when more references exist than were classified.
 */
public record LatheChangeImpact(
    String signature,
    boolean method,
    List<String> overrides,
    List<LatheLocation> overriddenBy,
    List<LatheLocation> redeclaredBy,
    int productionRefs,
    int testRefs,
    boolean referencesTruncated,
    List<String> affectedModules,
    List<String> relevantTests) {

  public LatheChangeImpact {
    ValidCheck.check().notNull(signature, "signature").validate();
    overrides = List.copyOf(overrides);
    overriddenBy = List.copyOf(overriddenBy);
    redeclaredBy = List.copyOf(redeclaredBy);
    affectedModules = List.copyOf(affectedModules);
    relevantTests = List.copyOf(relevantTests);
  }
}
