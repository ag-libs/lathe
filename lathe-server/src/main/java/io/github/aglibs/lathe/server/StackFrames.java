package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

// The stack frames in a run's output, each resolved to the source file of its class. The frames
// are program output, not Java source, so a pattern reads them:
// "at [loader/][module/]pkg.Cls.method(File.java:N)", with whatever indentation and trailing
// "~[jar]" the JVM printed (a named class loader's prefix may be "loader//").
final class StackFrames {

  private static final Pattern FRAME =
      Pattern.compile(
          "^\\s*at\\s+(?:[^\\s/(]*/)*([\\w$.]+)\\.[\\w$<>]+\\(([\\w$]+\\.java):(\\d+)\\)");

  private StackFrames() {}

  // A frame read off one line: the binary name of its class, the file it names, its 1-based line,
  // and the columns of its "File.java:N" span, the link an editor shows.
  record Frame(String binaryName, String fileName, int line, int startColumn, int endColumn) {

    Frame {
      ValidCheck.check()
          .notNull(binaryName, "binaryName")
          .notNull(fileName, "fileName")
          .assertTrue(line > 0, "line")
          .assertTrue(startColumn < endColumn, "startColumn")
          .validate();
    }

    String packageName() {
      final int lastDot = binaryName.lastIndexOf('.');
      return lastDot >= 0 ? binaryName.substring(0, lastDot) : "";
    }

    String topLevelName() {
      final String simple = binaryName.substring(binaryName.lastIndexOf('.') + 1);
      final int nested = simple.indexOf('$');
      return nested >= 0 ? simple.substring(0, nested) : simple;
    }
  }

  // A frame of the lathe.stack.resolve answer: the line it is on (its index in the request), the
  // source it resolved to, its 1-based line there, and the span to link (UTF-16 columns, as LSP).
  record Resolved(int index, String uri, int line, int startColumn, int endColumn) {

    Resolved {
      ValidCheck.check().notNull(uri, "uri").validate();
    }
  }

  static Optional<Frame> parse(final String text) {
    final var matcher = FRAME.matcher(text);
    if (!matcher.find()) {
      return Optional.empty();
    }

    return Optional.of(
        new Frame(
            matcher.group(1),
            matcher.group(2),
            Integer.parseInt(matcher.group(3)),
            matcher.start(2),
            matcher.end(3)));
  }

  // Every line that is a frame whose class has a source among the roots: its own name's file first
  // (a nested class's top-level type), else the file the frame names in that package (a second
  // top-level class in another file). A trace repeats its classes, so each is looked up once.
  static List<Resolved> resolve(final List<String> lines, final List<Path> sourceRoots) {
    final var sources = new HashMap<String, Optional<Path>>();
    final var resolved = new ArrayList<Resolved>();
    for (int index = 0; index < lines.size(); index++) {
      final Optional<Frame> frame = parse(lines.get(index));
      if (frame.isEmpty()) {
        continue;
      }

      final Frame found = frame.get();
      final Optional<Path> source =
          sources.computeIfAbsent(
              "%s/%s".formatted(found.binaryName(), found.fileName()),
              key -> sourceOf(found, sourceRoots));
      final int lineIndex = index;
      source.ifPresent(
          path ->
              resolved.add(
                  new Resolved(
                      lineIndex,
                      LatheUri.fromPath(path),
                      found.line(),
                      found.startColumn(),
                      found.endColumn())));
    }

    return List.copyOf(resolved);
  }

  // The fallback is skipped when the frame names its class's own file: the first lookup was it.
  private static Optional<Path> sourceOf(final Frame frame, final List<Path> sourceRoots) {
    final Optional<Path> own = TypeSourceLocator.findSourceFile(frame.binaryName(), sourceRoots);
    if (own.isPresent() || frame.fileName().equals("%s.java".formatted(frame.topLevelName()))) {
      return own;
    }

    return namedFile(frame, sourceRoots);
  }

  private static Optional<Path> namedFile(final Frame frame, final List<Path> sourceRoots) {
    final Path relative = Path.of(frame.packageName().replace('.', '/')).resolve(frame.fileName());
    return sourceRoots.stream()
        .map(root -> root.resolve(relative))
        .filter(Files::isRegularFile)
        .findFirst();
  }
}
