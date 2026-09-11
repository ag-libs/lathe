package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

// Turns resolved occurrence locations into a rename WorkspaceEdit and validates the new name. A
// rename is a semantically-resolved substitution: every occurrence range (declaration included) is
// replaced with the new identifier, so no structural rewrite is needed.
public final class RenameProvider {

  private RenameProvider() {}

  public static boolean isValidName(final String name) {
    return name != null && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
  }

  // The identifier token under the cursor — the range prepareRename returns for the client to
  // pre-fill and highlight. Null when the cursor is not on an identifier.
  public static Range identifierRange(final String content, final Position pos) {
    final int offset = SourceLocator.toOffset(content, pos.getLine(), pos.getCharacter());
    final int start = SourceLocator.identifierStart(content, offset);
    final int end = SourceLocator.identifierEnd(content, offset);
    if (start >= end) {
      return null;
    }

    return new Range(
        SourceLocator.offsetToPosition(content, start),
        SourceLocator.offsetToPosition(content, end));
  }

  public static WorkspaceEdit toWorkspaceEdit(
      final List<Location> occurrences, final String newName) {
    final Map<String, List<TextEdit>> changes =
        occurrences.stream()
            .collect(
                Collectors.groupingBy(
                    Location::getUri,
                    Collectors.mapping(
                        occurrence -> new TextEdit(occurrence.getRange(), newName),
                        Collectors.toList())));
    final var edit = new WorkspaceEdit();
    edit.setChanges(changes);
    return edit;
  }
}
