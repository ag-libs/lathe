package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.server.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * The imports fix_imports added to a file, and the names it left: ambiguous ones with their
 * candidates, unknown ones with none.
 */
public record LatheImports(String path, List<String> added, List<MissingImport> unresolved) {
  public LatheImports {
    ValidCheck.check()
        .notNull(path, "path")
        .notNull(added, "added")
        .notNull(unresolved, "unresolved")
        .validate();
    added = List.copyOf(added);
    unresolved = List.copyOf(unresolved);
  }
}
