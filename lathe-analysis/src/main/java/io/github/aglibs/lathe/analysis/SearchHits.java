package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// What a search found in the files it attributed, and the declarations among them that expose the
// target's family to files that never name it (see FamilyExposure), each as Type.member.
public record SearchHits<T>(List<T> hits, List<String> exposures) {

  public SearchHits {
    ValidCheck.check().notNull(hits, "hits").notNull(exposures, "exposures").validate();
    hits = List.copyOf(hits);
    exposures = List.copyOf(exposures);
  }

  public static <T> SearchHits<T> merge(final List<SearchHits<T>> parts) {
    return new SearchHits<>(
        parts.stream().flatMap(part -> part.hits().stream()).toList(),
        parts.stream().flatMap(part -> part.exposures().stream()).distinct().toList());
  }
}
