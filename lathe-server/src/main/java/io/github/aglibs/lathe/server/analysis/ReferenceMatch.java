package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;

// alsoOverrides: for an override's declaration, what it also overrides outside the searched family
// (Type.method), which a rename must not leave behind; empty for every other match.
public record ReferenceMatch(
    String uri, Range range, ReferenceRole role, List<String> alsoOverrides) {

  public ReferenceMatch {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(range, "range")
        .notNull(role, "role")
        .notNull(alsoOverrides, "alsoOverrides")
        .validate();
    alsoOverrides = List.copyOf(alsoOverrides);
    final var start = range.getStart();
    final var end = range.getEnd();
    ValidCheck.check().notNull(start, "range.start").notNull(end, "range.end").validate();
    ValidCheck.check()
        .assertTrue(start.getLine() >= 0, "range.start.line must be non-negative")
        .assertTrue(start.getCharacter() >= 0, "range.start.character must be non-negative")
        .assertTrue(end.getLine() >= 0, "range.end.line must be non-negative")
        .assertTrue(end.getCharacter() >= 0, "range.end.character must be non-negative")
        .assertTrue(
            start.getLine() < end.getLine()
                || (start.getLine() == end.getLine() && start.getCharacter() <= end.getCharacter()),
            "range start must not be after end")
        .validate();
  }

  public Location toLocation() {
    return new Location(uri, range);
  }

  public DocumentHighlight toHighlight() {
    return new DocumentHighlight(range, kindFor(role));
  }

  private static DocumentHighlightKind kindFor(final ReferenceRole role) {
    return switch (role) {
      case READ -> DocumentHighlightKind.Read;
      case WRITE -> DocumentHighlightKind.Write;
      case DECLARATION, IMPORT, SHARED_STATIC_IMPORT, INVOCATION, TYPE_USE ->
          DocumentHighlightKind.Text;
    };
  }
}
