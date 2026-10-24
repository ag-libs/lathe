package io.github.aglibs.lathe.core.schema;

import java.util.List;

/**
 * A module's test-class filter, as the build's own test run applies it: class-name regexes (full
 * match, binary names with {@code $}) that a package or module run must match one of, and must
 * match none of. Written at sync to {@code .lathe/<module>/test-filter.json}.
 */
public record TestFilterData(List<String> classNameIncludes, List<String> classNameExcludes) {

  public TestFilterData {
    classNameIncludes = classNameIncludes != null ? List.copyOf(classNameIncludes) : List.of();
    classNameExcludes = classNameExcludes != null ? List.copyOf(classNameExcludes) : List.of();
  }
}
