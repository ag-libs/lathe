package io.github.aglibs.lathe.analysis;

import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

// Where new import lines go: each at its sorted place in its group (static or not), so an ordered
// import block stays ordered and a build's import-order check still passes. A file that splits a
// kind into blank-line-separated groups by package (Eclipse's or IntelliJ's import order) keeps
// them: a new import joins its package's group, or gets one of its own. They are anchored at
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

  // One edit per position, its imports in order: edits at one position apply in no defined order,
  // and two insertions can share one (a group's end, and a new group below it).
  public List<TextEdit> edits(final List<String> spellings) {
    return spellings.stream()
        .sorted(ORDER)
        .collect(Collectors.groupingBy(this::insertionFor, LinkedHashMap::new, Collectors.toList()))
        .entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                entry -> entry.getKey().at(),
                entry -> entry.getKey().text(entry.getValue()),
                String::concat,
                LinkedHashMap::new))
        .entrySet()
        .stream()
        .map(entry -> new TextEdit(new Range(entry.getKey(), entry.getKey()), entry.getValue()))
        .toList();
  }

  private Insertion insertionFor(final String spelling) {
    final List<ImportLine> kind =
        imports.stream().filter(line -> line.isStatic() == isStatic(spelling)).toList();
    if (kind.isEmpty()) {
      return otherGroupEdge(isStatic(spelling));
    }

    final List<List<ImportLine>> groups = groups(kind);
    return groupFor(groups, spelling)
        .map(group -> sortedInto(group, spelling))
        .orElseGet(() -> newGroup(groups, spelling));
  }

  // The kind's imports in blank-line-separated runs.
  private static List<List<ImportLine>> groups(final List<ImportLine> kind) {
    final List<List<ImportLine>> groups = new ArrayList<>();
    for (final ImportLine line : kind) {
      if (groups.isEmpty()
          || line.start().getLine()
              > CollectionUtil.last(CollectionUtil.last(groups)).end().getLine() + 1) {
        groups.add(new ArrayList<>());
      }

      CollectionUtil.last(groups).add(line);
    }

    return groups.stream().map(List::copyOf).toList();
  }

  // The group a new import joins: the only one, as a single block may be a formatter's that never
  // groups (google-java-format); else one holding its top-level package, sharing the longest
  // prefix with it; else one mixing packages (IntelliJ's "all other imports"). Empty when it needs
  // a group of its own.
  private static Optional<List<ImportLine>> groupFor(
      final List<List<ImportLine>> groups, final String spelling) {
    if (groups.size() == 1) {
      return Optional.of(groups.get(0));
    }

    final String top = topLevelPackage(spelling);
    return groups.stream()
        .filter(
            group -> group.stream().anyMatch(line -> topLevelPackage(line.spelling()).equals(top)))
        .max(Comparator.comparingInt(group -> sharedSegments(group, spelling)))
        .or(
            () ->
                groups.stream()
                    .filter(
                        group ->
                            group.stream()
                                    .map(line -> topLevelPackage(line.spelling()))
                                    .distinct()
                                    .count()
                                > 1)
                    .findFirst());
  }

  private static Insertion sortedInto(final List<ImportLine> group, final String spelling) {
    return group.stream()
        .filter(line -> line.spelling().compareTo(spelling) > 0)
        .findFirst()
        .map(line -> Insertion.before(line.start()))
        .orElseGet(() -> Insertion.after(CollectionUtil.last(group).end()));
  }

  // Before the first group whose imports sort after it, else below the last group.
  private static Insertion newGroup(final List<List<ImportLine>> groups, final String spelling) {
    return groups.stream()
        .filter(group -> group.get(0).spelling().compareTo(spelling) > 0)
        .findFirst()
        .map(group -> Insertion.groupBefore(group.get(0).start()))
        .orElseGet(
            () -> Insertion.belowBlankLine(CollectionUtil.last(CollectionUtil.last(groups)).end()));
  }

  private static String topLevelPackage(final String spelling) {
    final String name = isStatic(spelling) ? spelling.substring(STATIC_PREFIX.length()) : spelling;
    final int dot = name.indexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }

  // The most leading package segments an import of the group shares with the spelling.
  private static int sharedSegments(final List<ImportLine> group, final String spelling) {
    final List<String> segments = List.of(spelling.split("\\."));
    return group.stream()
        .mapToInt(line -> commonPrefix(segments, List.of(line.spelling().split("\\."))))
        .max()
        .orElse(0);
  }

  private static int commonPrefix(final List<String> first, final List<String> second) {
    return (int)
        IntStream.range(0, Math.min(first.size(), second.size()))
            .takeWhile(i -> first.get(i).equals(second.get(i)))
            .count();
  }

  // The first of its group: statics before the other imports, the others after the statics; the
  // first import of a file goes a blank line below the package clause, or at the very start.
  private Insertion otherGroupEdge(final boolean isStatic) {
    if (imports.isEmpty()) {
      return packageEnd.equals(NO_PACKAGE)
          ? Insertion.before(NO_PACKAGE)
          : Insertion.belowBlankLine(packageEnd);
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
  // line, a blank line after them when they open a group; or behind an import or the package
  // clause, each starting a line, a blank line first below the package clause or a group.
  private record Insertion(Position at, String lead, String line, String trail) {
    private Insertion {
      ValidCheck.check()
          .notNull(at, "at")
          .notNull(lead, "lead")
          .notNull(line, "line")
          .notNull(trail, "trail")
          .validate();
    }

    static Insertion before(final Position at) {
      return new Insertion(at, "", "import %s;\n", "");
    }

    static Insertion groupBefore(final Position at) {
      return new Insertion(at, "", "import %s;\n", "\n");
    }

    static Insertion after(final Position at) {
      return new Insertion(at, "", "\nimport %s;", "");
    }

    static Insertion belowBlankLine(final Position at) {
      return new Insertion(at, "\n", "\nimport %s;", "");
    }

    String text(final List<String> spellings) {
      return spellings.stream().map(line::formatted).collect(Collectors.joining("", lead, trail));
    }
  }
}
