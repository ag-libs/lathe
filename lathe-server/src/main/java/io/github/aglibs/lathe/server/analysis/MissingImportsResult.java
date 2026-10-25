package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import org.eclipse.lsp4j.Position;

/**
 * Every unresolved type name in a file with its importable fully-qualified candidates, and the
 * file's import layout, which places the lines to add. A candidate list of one is unambiguous
 * (auto-add); more than one needs a user choice; empty means the name could not be resolved.
 */
public record MissingImportsResult(ImportLayout layout, List<MissingImport> items) {

  public MissingImportsResult {
    ValidCheck.check().notNull(layout, "layout").notNull(items, "items").validate();
    items = List.copyOf(items);
  }

  public static MissingImportsResult empty() {
    return new MissingImportsResult(new ImportLayout(List.of(), new Position(0, 0)), List.of());
  }

  public record MissingImport(String name, List<String> candidates) {
    public MissingImport {
      ValidCheck.check().notNull(name, "name").notNull(candidates, "candidates").validate();
      candidates = List.copyOf(candidates);
    }
  }
}
