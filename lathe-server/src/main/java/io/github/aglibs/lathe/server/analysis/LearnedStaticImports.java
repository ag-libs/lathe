package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Map;

// The static imports one source scope (test or main) declares, each spelled as in its import line
// (`org.mockito.Mockito.when`, `org.mockito.Mockito.*`) with the number of that scope's files that
// declare it.
public record LearnedStaticImports(Map<String, Integer> counts) {

  public LearnedStaticImports {
    ValidCheck.check().notNull(counts, "counts").validate();
    counts = Map.copyOf(counts);
  }

  public static LearnedStaticImports empty() {
    return new LearnedStaticImports(Map.of());
  }
}
