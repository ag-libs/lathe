package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SourceFileScanner {

  private static final Logger LOG = Logger.getLogger(SourceFileScanner.class.getName());

  private SourceFileScanner() {}

  record Candidate(String uri, String content) {
    Candidate {
      ValidCheck.check().notBlank(uri, "uri").notNull(content, "content").validate();
    }
  }

  static List<Candidate> findCandidates(
      final List<Path> sourceRoots, final Set<String> openUris, final String simpleName) {
    return sourceRoots.stream()
        .filter(Files::isDirectory)
        .flatMap(root -> walkJavaFiles(root).stream())
        .filter(path -> !openUris.contains(path.toUri().toString()))
        .flatMap(path -> readIfContainsToken(path, simpleName).stream())
        .toList();
  }

  private static List<Path> walkJavaFiles(final Path root) {
    try (final var stream = Files.walk(root)) {
      return stream.filter(p -> p.toString().endsWith(".java")).toList();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[references] failed to walk source root: " + root);
      return List.of();
    }
  }

  private static List<Candidate> readIfContainsToken(final Path path, final String simpleName) {
    try {
      final var content = Files.readString(path);
      return SourceLocator.findIdentifierFrom(content, 0, simpleName) >= 0
          ? List.of(new Candidate(path.toUri().toString(), content))
          : List.of();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[references] failed to read candidate file: " + path);
      return List.of();
    }
  }
}
