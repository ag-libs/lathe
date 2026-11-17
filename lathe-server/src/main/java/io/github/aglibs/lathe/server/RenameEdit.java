package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.WorkspaceEdit;

// A rename's edit, null when there is nothing to edit, and the occurrences it left in generated
// source for their generator to rewrite.
record RenameEdit(WorkspaceEdit edit, List<Location> leftInGenerated) {

  RenameEdit {
    ValidCheck.check().notNull(leftInGenerated, "leftInGenerated").validate();
    leftInGenerated = List.copyOf(leftInGenerated);
  }

  static RenameEdit none() {
    return new RenameEdit(null, List.of());
  }
}
