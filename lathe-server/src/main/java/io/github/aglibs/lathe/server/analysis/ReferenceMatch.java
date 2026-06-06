package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;

public record ReferenceMatch(String uri, Range range, ReferenceRole role) {

  public ReferenceMatch {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(range, "range")
        .notNull(role, "role")
        .validate();
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
}
