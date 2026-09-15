package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import org.eclipse.lsp4j.Range;

/**
 * Every unresolved type name in a file with its importable fully-qualified candidates. {@code
 * insertionRange} is where an import line belongs (null only when there is nothing to import). A
 * candidate list of one is unambiguous (auto-add); more than one needs a user choice; empty means
 * the name could not be resolved.
 */
public record MissingImportsResult(Range insertionRange, List<MissingImport> items) {

  public MissingImportsResult {
    // insertionRange is intentionally nullable (the empty result); items never is.
    ValidCheck.check().notNull(items, "items").validate();
    items = List.copyOf(items);
  }

  public static MissingImportsResult empty() {
    return new MissingImportsResult(null, List.of());
  }

  public record MissingImport(String name, List<String> candidates) {
    public MissingImport {
      ValidCheck.check().notNull(name, "name").notNull(candidates, "candidates").validate();
      candidates = List.copyOf(candidates);
    }
  }
}
