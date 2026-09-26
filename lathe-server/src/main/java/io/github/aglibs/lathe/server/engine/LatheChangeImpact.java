package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * The pre-edit impact of a symbol: its rendered signature, its override/implementation family, how
 * many production vs test references it has, the reactor modules that use it, and the relevant test
 * classes. Answers "what will change if I touch this?" before an edit, javac-accurate and
 * cross-module. Reference counts reflect the (capped) reference set — {@code referencesTruncated}
 * is true when more references exist than were classified.
 */
public record LatheChangeImpact(
    String signature,
    List<LatheLocation> overrideFamily,
    int productionRefs,
    int testRefs,
    boolean referencesTruncated,
    List<String> affectedModules,
    List<String> relevantTests) {

  public LatheChangeImpact {
    ValidCheck.check().notNull(signature, "signature").validate();
    overrideFamily = List.copyOf(overrideFamily);
    affectedModules = List.copyOf(affectedModules);
    relevantTests = List.copyOf(relevantTests);
  }
}
