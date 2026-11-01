package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

// Where a name survives as text after a rename, which changes only code that resolves to the
// symbol: Java string literals that name it (the name alone, as getMethod or a key takes it, or
// qualified, as Class.forName does), found through javac's trees, and qualified in resource files
// (META-INF/services, framework XML and properties). Prose that merely contains the word is left
// out: for a common name it is hundreds of strings.
public final class TextMentions {

  private static final Path SERVICES_DIR = Path.of("META-INF", "services");
  private static final String TEXT_BLOCK = "\"\"\"";

  // A resource file this large is data, not configuration naming a class.
  private static final long MAX_RESOURCE_BYTES = 1_000_000;

  private TextMentions() {}

  // Where to look: the reactor sources whose tokens include the name, and the resource roots. Read
  // from the session on its worker; the scan itself reads files and parses, so it runs elsewhere.
  public record Sources(List<Path> javaFiles, List<Path> resourceDirs) {
    public Sources {
      ValidCheck.check()
          .notNull(javaFiles, "javaFiles")
          .notNull(resourceDirs, "resourceDirs")
          .validate();
      javaFiles = List.copyOf(javaFiles);
      resourceDirs = List.copyOf(resourceDirs);
    }
  }

  public static List<Location> find(final Sources sources, final String name) {
    return Stream.concat(
            inStringLiterals(sources.javaFiles(), name).stream(),
            inResources(sources.resourceDirs(), name).stream())
        .toList();
  }

  public static List<Location> inStringLiterals(final List<Path> javaFiles, final String name) {
    try (var parser = new SourceParser()) {
      return javaFiles.stream()
          .filter(file -> mayQuote(file, name))
          .flatMap(
              file -> parser.parseFile(file, (trees, cu) -> literals(trees, cu, name)).stream())
          .flatMap(List::stream)
          .toList();
    }
  }

  // A common name is in thousands of files; only a line that quotes or qualifies it can hold such a
  // literal, so only files with one are parsed, and javac decides.
  private static boolean mayQuote(final Path file, final String name) {
    final String quoted = "\"%s\"".formatted(name);
    final String text = readText(file).orElse("");
    // A text block's lines carry no quote of their own.
    final boolean textBlock = text.contains(TEXT_BLOCK);
    return text.lines()
        .anyMatch(
            line ->
                (textBlock || line.indexOf('"') >= 0)
                    && (line.contains(quoted)
                        || wordStarts(line, name).anyMatch(at -> qualified(line, at))));
  }

  private static List<Location> literals(
      final Trees trees, final CompilationUnitTree cu, final String name) {
    final var found = new ArrayList<Location>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitLiteral(final LiteralTree node, final Void ignored) {
        if (node.getValue() instanceof final String text && namesIt(text, name)) {
          final long start = trees.getSourcePositions().getStartPosition(cu, node);
          final Position at = SourceLocator.offsetToPosition(cu, start);
          found.add(new Location(cu.getSourceFile().toUri().toString(), new Range(at, at)));
        }

        return null;
      }
    }.scan(cu, null);
    return List.copyOf(found);
  }

  // A resource names a class or member qualified (com.example.OldName, bean.oldName) or, as a
  // META-INF/services file does, by its file name.
  public static List<Location> inResources(final List<Path> resourceDirs, final String name) {
    return resourceDirs.stream()
        .flatMap(
            dir -> IOUtil.unchecked(() -> FileUtil.walkFiles(dir, Files::isRegularFile)).stream())
        .flatMap(file -> resourceMentions(file, name).stream())
        .toList();
  }

  private static List<Location> resourceMentions(final Path file, final String name) {
    final String uri = file.toUri().toString();
    if (file.getParent().endsWith(SERVICES_DIR)
        && file.getFileName().toString().endsWith(".%s".formatted(name))) {
      final var top = new Position(0, 0);
      return List.of(new Location(uri, new Range(top, top)));
    }

    final List<String> lines = readText(file).map(text -> text.lines().toList()).orElse(List.of());
    return IntStream.range(0, lines.size())
        .filter(i -> wordStarts(lines.get(i), name).anyMatch(at -> qualified(lines.get(i), at)))
        .mapToObj(i -> new Location(uri, new Range(new Position(i, 0), new Position(i, 0))))
        .toList();
  }

  private static Optional<String> readText(final Path file) {
    final byte[] bytes =
        IOUtil.unchecked(
            () -> Files.size(file) > MAX_RESOURCE_BYTES ? new byte[0] : Files.readAllBytes(file));
    final String text = new String(bytes, StandardCharsets.UTF_8);
    return text.indexOf('\0') >= 0 ? Optional.empty() : Optional.of(text);
  }

  private static boolean namesIt(final String literal, final String name) {
    return literal.equals(name) || wordStarts(literal, name).anyMatch(at -> qualified(literal, at));
  }

  // com.example.Name, bean.name, Type#name.
  private static boolean qualified(final String text, final int at) {
    return at > 0 && (text.charAt(at - 1) == '.' || text.charAt(at - 1) == '#');
  }

  // Where name stands as a whole identifier in text, left to right.
  public static IntStream wordStarts(final String text, final String name) {
    return IntStream.rangeClosed(0, text.length() - name.length())
        .filter(at -> text.startsWith(name, at))
        .filter(at -> SourceLocator.identifierStart(text, at) == at)
        .filter(at -> SourceLocator.identifierEnd(text, at) == at + name.length());
  }
}
