package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.TextMentions;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheUri;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

// The Java files in the workspace that no synced module compiles: those of a module the build's
// profiles leave out, examples, templates. A rename neither edits nor checks them, so where they
// name the symbol is where a full build may still break.
final class OutsideSources {

  // Build output; hidden directories (.git, .lathe) are skipped too.
  private static final Set<String> OUTPUT_DIRS = Set.of("target", "build");

  private OutsideSources() {}

  // The first place each such file names it, ordered by file.
  static List<Location> mentioning(
      final Path root, final List<Path> sourceRoots, final String name) {
    final Set<Path> synced = Set.copyOf(sourceRoots);
    final var found = new ArrayList<Location>();
    IOUtil.unchecked(
        () ->
            Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                  @Override
                  public FileVisitResult preVisitDirectory(
                      final Path dir, final BasicFileAttributes attrs) {
                    return dir.equals(root) || !skipped(dir, synced)
                        ? FileVisitResult.CONTINUE
                        : FileVisitResult.SKIP_SUBTREE;
                  }

                  @Override
                  public FileVisitResult visitFile(
                      final Path file, final BasicFileAttributes attrs) {
                    if (FileUtil.isJavaFile(file)) {
                      firstMention(file, name).ifPresent(found::add);
                    }

                    return FileVisitResult.CONTINUE;
                  }
                }));
    return found.stream().sorted(Comparator.comparing(Location::getUri)).toList();
  }

  private static boolean skipped(final Path dir, final Set<Path> synced) {
    final String dirName = dir.getFileName().toString();
    return synced.contains(dir) || dirName.startsWith(".") || OUTPUT_DIRS.contains(dirName);
  }

  private static Optional<Location> firstMention(final Path file, final String name) {
    final String text =
        new String(IOUtil.unchecked(() -> Files.readAllBytes(file)), StandardCharsets.UTF_8);
    if (!text.contains(name)) {
      return Optional.empty();
    }

    final List<String> lines = text.lines().toList();
    return IntStream.range(0, lines.size())
        .boxed()
        .flatMap(
            line ->
                TextMentions.wordStarts(lines.get(line), name)
                    .limit(1)
                    .mapToObj(column -> new Position(line, column)))
        .findFirst()
        .map(at -> new Location(LatheUri.fromPath(file), new Range(at, at)));
  }
}
