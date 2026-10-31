package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.SnippetTextEdit;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Turns resolved occurrence locations into a rename WorkspaceEdit and validates the new name. A
// rename is a semantically-resolved substitution: every occurrence range (declaration included) is
// replaced with the new identifier, so no structural rewrite is needed.
public final class RenameProvider {

  private static final Set<String> NOT_TYPE_NAMES =
      Set.of("permits", "record", "sealed", "var", "yield");

  private RenameProvider() {}

  public static boolean isValidName(final String name) {
    return name != null && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
  }

  // Also not a restricted identifier, which no type can take (JLS 3.9).
  public static boolean isValidTypeName(final String name) {
    return isValidName(name) && !NOT_TYPE_NAMES.contains(name);
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
    final var edit = new WorkspaceEdit();
    edit.setChanges(editsByUri(occurrences, newName));
    return edit;
  }

  // A type rename that also moves the declaring file (`Foo.java` -> `Bar.java`). documentChanges is
  // the only WorkspaceEdit form that carries a resource operation; the text edits apply first (on
  // the old URI), then the file is renamed.
  public static WorkspaceEdit toWorkspaceEditWithFileRename(
      final List<Location> occurrences,
      final String newName,
      final String oldFileUri,
      final String newFileUri) {
    final List<Either<TextDocumentEdit, ResourceOperation>> changes =
        Stream.concat(
                editsByUri(occurrences, newName).entrySet().stream()
                    .map(RenameProvider::textDocumentEdit),
                Stream.of(
                    Either.<TextDocumentEdit, ResourceOperation>forRight(
                        new RenameFile(oldFileUri, newFileUri))))
            .toList();
    final var edit = new WorkspaceEdit();
    edit.setDocumentChanges(changes);
    return edit;
  }

  private static Either<TextDocumentEdit, ResourceOperation> textDocumentEdit(
      final Map.Entry<String, List<TextEdit>> fileEdits) {
    final List<Either<TextEdit, SnippetTextEdit>> edits =
        fileEdits.getValue().stream()
            .map(edit -> Either.<TextEdit, SnippetTextEdit>forLeft(edit))
            .collect(Collectors.toUnmodifiableList());
    return Either.forLeft(
        new TextDocumentEdit(new VersionedTextDocumentIdentifier(fileEdits.getKey(), null), edits));
  }

  private static Map<String, List<TextEdit>> editsByUri(
      final List<Location> occurrences, final String newName) {
    return occurrences.stream()
        .collect(
            Collectors.groupingBy(
                Location::getUri,
                Collectors.mapping(
                    occurrence -> new TextEdit(occurrence.getRange(), newName),
                    Collectors.toUnmodifiableList())));
  }

  // Text edits by URI, from documentChanges when the edit carries a file move, else from changes.
  public static Map<String, List<TextEdit>> textEdits(final WorkspaceEdit edit) {
    if (edit == null) {
      return Map.of();
    }

    if (edit.getDocumentChanges() == null) {
      return edit.getChanges() == null ? Map.of() : Map.copyOf(edit.getChanges());
    }

    return edit.getDocumentChanges().stream()
        .filter(Either::isLeft)
        .map(Either::getLeft)
        .collect(
            Collectors.toUnmodifiableMap(
                change -> change.getTextDocument().getUri(), RenameProvider::plainEdits));
  }

  // A rename emits plain text edits only, never snippets.
  private static List<TextEdit> plainEdits(final TextDocumentEdit change) {
    return change.getEdits().stream().map(Either::getLeft).toList();
  }

  // A moved file's old URI to its new one.
  public static Map<String, String> fileMoves(final WorkspaceEdit edit) {
    if (edit == null || edit.getDocumentChanges() == null) {
      return Map.of();
    }

    return edit.getDocumentChanges().stream()
        .filter(Either::isRight)
        .map(Either::getRight)
        .filter(RenameFile.class::isInstance)
        .map(RenameFile.class::cast)
        .collect(Collectors.toUnmodifiableMap(RenameFile::getOldUri, RenameFile::getNewUri));
  }
}
