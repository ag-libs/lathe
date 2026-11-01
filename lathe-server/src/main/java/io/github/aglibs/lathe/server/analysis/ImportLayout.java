package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

// Where new import lines go: each at its sorted place in its group (static or not), so an ordered
// import block stays ordered and a build's import-order check still passes. They are anchored at
// the exact starts and ends of the existing imports and the package clause, not at line starts, so
// a file that puts those on one line with other code stays valid. A spelling is a qualified name,
// prefixed with "static " for a static import. packageEnd is where the package clause ends, or
// NO_PACKAGE (where no clause can end) in a file without one.
public record ImportLayout(List<ImportLine> imports, Position packageEnd) {

  public static final Position NO_PACKAGE = new Position(0, 0);
  private static final String STATIC_PREFIX = "static ";
  // Statics first, as google-java-format orders them, then by name.
  private static final Comparator<String> ORDER =
      Comparator.comparing((String spelling) -> !isStatic(spelling))
          .thenComparing(Comparator.naturalOrder());

  public ImportLayout {
    ValidCheck.check().notNull(imports, "imports").notNull(packageEnd, "packageEnd").validate();
    imports = List.copyOf(imports);
  }

  public static String spelling(final String qualifiedName, final boolean isStatic) {
    return isStatic ? STATIC_PREFIX + qualifiedName : qualifiedName;
  }

  public TextEdit edit(final String spelling) {
    return edits(List.of(spelling)).get(0);
  }

  // One edit per insertion point, its imports in order: edits at one position apply in no defined
  // order.
  public List<TextEdit> edits(final List<String> spellings) {
    return spellings.stream()
        .sorted(ORDER)
        .collect(Collectors.groupingBy(this::insertionFor, LinkedHashMap::new, Collectors.toList()))
        .entrySet()
        .stream()
        .map(entry -> entry.getKey().edit(entry.getValue()))
        .toList();
  }

  private Insertion insertionFor(final String spelling) {
    final List<ImportLine> group =
        imports.stream().filter(line -> line.isStatic() == isStatic(spelling)).toList();
    if (group.isEmpty()) {
      return otherGroupEdge(isStatic(spelling));
    }

    return group.stream()
        .filter(line -> line.spelling().compareTo(spelling) > 0)
        .findFirst()
        .map(line -> Insertion.before(line.start()))
        .orElseGet(() -> Insertion.after(CollectionUtil.last(group).end()));
  }

  // The first of its group: statics before the other imports, the others after the statics; the
  // first import of a file goes a blank line below the package clause, or at the very start.
  private Insertion otherGroupEdge(final boolean isStatic) {
    if (imports.isEmpty()) {
      return packageEnd.equals(NO_PACKAGE)
          ? Insertion.before(NO_PACKAGE)
          : Insertion.belowPackage(packageEnd);
    }

    return isStatic
        ? Insertion.before(imports.get(0).start())
        : Insertion.after(CollectionUtil.last(imports).end());
  }

  private static boolean isStatic(final String spelling) {
    return spelling.startsWith(STATIC_PREFIX);
  }

  // An existing import, where it starts and ends.
  public record ImportLine(String spelling, Position start, Position end) {
    public ImportLine {
      ValidCheck.check()
          .notNull(spelling, "spelling")
          .notNull(start, "start")
          .notNull(end, "end")
          .validate();
    }

    boolean isStatic() {
      return ImportLayout.isStatic(spelling);
    }
  }

  // New import lines at one position: in front of an import (or the file's start), each ending its
  // line; or behind the last import or the package clause, each starting a line, a blank line first
  // below the package clause.
  private record Insertion(Position at, String lead, String line) {
    private Insertion {
      ValidCheck.check().notNull(at, "at").notNull(lead, "lead").notNull(line, "line").validate();
    }

    static Insertion before(final Position at) {
      return new Insertion(at, "", "import %s;\n");
    }

    static Insertion after(final Position at) {
      return new Insertion(at, "", "\nimport %s;");
    }

    static Insertion belowPackage(final Position at) {
      return new Insertion(at, "\n", "\nimport %s;");
    }

    TextEdit edit(final List<String> spellings) {
      return new TextEdit(
          new Range(at, at),
          spellings.stream().map(line::formatted).collect(Collectors.joining("", lead, "")));
    }
  }
}
