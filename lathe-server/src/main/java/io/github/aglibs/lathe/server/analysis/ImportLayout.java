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
// import block stays ordered and a build's import-order check still passes. A spelling is a
// qualified name, "static " first for a static import. fallback is the line after the package
// clause, for a file without imports.
public record ImportLayout(List<ImportLine> imports, Position fallback) {

  private static final String STATIC_PREFIX = "static ";
  // Statics first, as google-java-format orders them, then by name.
  private static final Comparator<String> ORDER =
      Comparator.comparing((String spelling) -> !isStatic(spelling))
          .thenComparing(Comparator.naturalOrder());

  public ImportLayout {
    ValidCheck.check().notNull(imports, "imports").notNull(fallback, "fallback").validate();
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
        .collect(Collectors.groupingBy(this::positionFor, LinkedHashMap::new, Collectors.toList()))
        .entrySet()
        .stream()
        .map(
            entry ->
                new TextEdit(
                    new Range(entry.getKey(), entry.getKey()),
                    entry.getValue().stream()
                        .map("import %s;\n"::formatted)
                        .collect(Collectors.joining("", separator(), ""))))
        .toList();
  }

  // The first import of a file goes after the package clause, a blank line below it; the fallback
  // is past line 0 exactly when a package clause is there.
  private String separator() {
    return imports.isEmpty() && fallback.getLine() > 0 ? "\n" : "";
  }

  private Position positionFor(final String spelling) {
    final List<ImportLine> group =
        imports.stream().filter(line -> line.isStatic() == isStatic(spelling)).toList();
    if (group.isEmpty()) {
      return otherGroupEdge(isStatic(spelling));
    }

    return group.stream()
        .filter(line -> line.spelling().compareTo(spelling) > 0)
        .findFirst()
        .map(line -> new Position(line.startLine(), 0))
        .orElseGet(() -> new Position(CollectionUtil.last(group).endLine() + 1, 0));
  }

  // The first of its group: statics before the other imports, the others after the statics.
  private Position otherGroupEdge(final boolean isStatic) {
    if (imports.isEmpty()) {
      return fallback;
    }

    return isStatic
        ? new Position(imports.get(0).startLine(), 0)
        : new Position(CollectionUtil.last(imports).endLine() + 1, 0);
  }

  private static boolean isStatic(final String spelling) {
    return spelling.startsWith(STATIC_PREFIX);
  }

  // An existing import, its 0-based first and last line.
  public record ImportLine(String spelling, int startLine, int endLine) {
    public ImportLine {
      ValidCheck.check().notNull(spelling, "spelling").validate();
    }

    boolean isStatic() {
      return ImportLayout.isStatic(spelling);
    }
  }
}
