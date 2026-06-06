package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Live token-to-URI index for reference candidate discovery.
 *
 * <p>Maps every Java identifier token to the set of source file URIs that contain it. Replaces the
 * per-request full disk scan with an O(1) lookup. Open-document updates keep the index current
 * without a file-system read.
 *
 * <p>Not thread-safe. All reads and writes must happen on the lathe-worker thread.
 */
final class ReferenceCandidateIndex {

  private static final Logger LOG = Logger.getLogger(ReferenceCandidateIndex.class.getName());

  private final Map<String, Set<String>> tokenToUris = new HashMap<>();
  private final Map<String, Set<String>> uriToTokens = new HashMap<>();

  static ReferenceCandidateIndex build(final List<ModuleSourceConfig> allConfigs) {
    final var t = Stopwatch.start();
    final var index = new ReferenceCandidateIndex();
    allConfigs.stream()
        .flatMap(config -> config.sourceRoots().stream())
        .distinct()
        .filter(Files::isDirectory)
        .forEach(index::indexRoot);
    LOG.fine(
        () ->
            "[candidate-index] built: %d file(s), %d token(s) %dms"
                .formatted(index.uriToTokens.size(), index.tokenToUris.size(), t.elapsedMs()));
    return index;
  }

  void update(final String uri, final String content) {
    remove(uri);
    final Set<String> tokens = extractTokens(content);
    uriToTokens.put(uri, tokens);
    tokens.forEach(token -> tokenToUris.computeIfAbsent(token, k -> new HashSet<>()).add(uri));
  }

  void remove(final String uri) {
    final var tokens = uriToTokens.remove(uri);
    if (tokens == null) {
      return;
    }

    tokens.forEach(
        token -> {
          final var uris = tokenToUris.get(token);
          if (uris != null) {
            uris.remove(uri);
            if (uris.isEmpty()) {
              tokenToUris.remove(token);
            }
          }
        });
  }

  Set<String> candidateUris(final String token) {
    return tokenToUris.getOrDefault(token, Set.of());
  }

  private void indexRoot(final Path root) {
    try (final var stream = Files.walk(root)) {
      stream
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  update(path.toUri().toString(), Files.readString(path));
                } catch (final IOException e) {
                  LOG.log(Level.FINE, e, () -> "[candidate-index] skipped: " + path);
                }
              });
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[candidate-index] failed to walk: " + root);
    }
  }

  private static Set<String> extractTokens(final String content) {
    final var tokens = new HashSet<String>();
    int i = 0;
    while (i < content.length()) {
      if (Character.isJavaIdentifierStart(content.charAt(i))) {
        final int end = SourceLocator.identifierEnd(content, i);
        tokens.add(content.substring(i, end));
        i = end;
      } else {
        i++;
      }
    }
    return tokens;
  }
}
