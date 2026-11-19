package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * The pre-edit impact of a symbol: its rendered signature, what kind of family it has, the family
 * (for a method, the methods it overrides by {@code Type#name(parameters)}, those that override it,
 * and the abstract ones that redeclare it; for a type, its subtypes in {@code overriddenBy}), how
 * many production vs test references it has, the reactor modules that use or implement it, and the
 * relevant test classes. Answers "what will change if I touch this?" before an edit, javac-accurate
 * and cross-module. The family lists are capped; their counts, like the reference counts, are of
 * all.
 */
public record LatheChangeImpact(
    String signature,
    Family family,
    List<String> overrides,
    List<LatheLocation> overriddenBy,
    int overriddenByCount,
    List<LatheLocation> redeclaredBy,
    int redeclaredByCount,
    int productionRefs,
    int testRefs,
    List<String> affectedModules,
    List<String> relevantTests) {

  public LatheChangeImpact {
    ValidCheck.check()
        .notNull(signature, "signature")
        .notNull(family, "family")
        .notNull(overriddenBy, "overriddenBy")
        .notNull(redeclaredBy, "redeclaredBy")
        .assertTrue(overriddenByCount >= overriddenBy.size(), "overriddenByCount")
        .assertTrue(redeclaredByCount >= redeclaredBy.size(), "redeclaredByCount")
        .validate();
    overrides = List.copyOf(overrides);
    overriddenBy = List.copyOf(overriddenBy);
    redeclaredBy = List.copyOf(redeclaredBy);
    affectedModules = List.copyOf(affectedModules);
    relevantTests = List.copyOf(relevantTests);
  }

  /**
   * What the symbol's family is: a method's overrides, a type's subtypes, or none, as for a field
   * or variable, which nothing can override.
   */
  public enum Family {
    METHOD,
    TYPE,
    NONE
  }
}
