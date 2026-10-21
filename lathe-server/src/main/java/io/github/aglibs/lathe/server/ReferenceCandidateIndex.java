package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Live token-to-URI index for reference candidate discovery.
 *
 * <p>Maps every Java identifier token to the set of source file URIs that contain it. Replaces the
 * per-request full disk scan with an O(1) lookup. Open-document updates keep the index current
 * without a file-system read; {@link #syncFromDisk} catches up with files changed outside the
 * editor.
 *
 * <p>Not thread-safe. All reads and writes must happen on the lathe-worker thread.
 */
final class ReferenceCandidateIndex {

  private static final Logger LOG = Logger.getLogger(ReferenceCandidateIndex.class.getName());

  private final Map<String, Set<String>> tokenToUris = new HashMap<>();
  private final Map<String, Set<String>> uriToTokens = new HashMap<>();
  // The mtime each file was read at, for files whose tokens came from disk; a file whose tokens
  // came from an editor buffer has none. Generated sources are left out: only a sync rewrites them.
  private final Map<Path, Long> diskMtimes = new HashMap<>();

  static ReferenceCandidateIndex build(final List<ModuleSourceConfig> allConfigs) {
    final var t = Stopwatch.start();
    final var index = new ReferenceCandidateIndex();
    allConfigs.stream()
        .flatMap(
            config ->
                config.searchRoots().stream()
                    .map(root -> Map.entry(root, !config.isGenerated(root))))
        .filter(root -> Files.isDirectory(root.getKey()))
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (first, ignored) -> first,
                LinkedHashMap::new))
        .forEach(index::indexRoot);
    LOG.fine(
        () ->
            "[candidate-index] built: %d file(s), %d token(s) %dms"
                .formatted(index.uriToTokens.size(), index.tokenToUris.size(), t.elapsedMs()));
    return index;
  }

  void update(final String uri, final String content) {
    remove(uri);
    index(uri, content);
  }

  // Re-reads every file whose mtime moved since it was read, indexes new ones, and drops the ones
  // gone, so a file changed outside the editor (an agent, an MCP rename, a branch switch) is a
  // candidate by its current text. Open files keep their buffer's tokens. Returns the files
  // re-read.
  List<Path> syncFromDisk(final Map<Path, Long> sourceMtimes, final Predicate<String> isOpen) {
    final List<Path> gone =
        diskMtimes.keySet().stream().filter(path -> !sourceMtimes.containsKey(path)).toList();
    final List<Path> moved =
        sourceMtimes.entrySet().stream()
            .filter(source -> !source.getValue().equals(diskMtimes.get(source.getKey())))
            .map(Map.Entry::getKey)
            .filter(path -> !isOpen.test(LatheUri.fromPath(path)))
            .toList();
    gone.forEach(path -> remove(LatheUri.fromPath(path)));
    moved.forEach(this::indexFromDisk);
    return moved;
  }

  private void index(final String uri, final String content) {
    final Set<String> tokens = extractTokens(content);
    uriToTokens.put(uri, tokens);
    for (final var token : tokens) {
      tokenToUris.computeIfAbsent(token, k -> new HashSet<>()).add(uri);
    }
  }

  void remove(final String uri) {
    if (LatheUri.isFileUri(uri)) {
      diskMtimes.remove(LatheUri.toPath(uri));
    }

    final var tokens = uriToTokens.remove(uri);
    if (tokens == null) {
      return;
    }

    for (final var token : tokens) {
      final Set<String> uris = tokenToUris.get(token);
      if (uris != null) {
        uris.remove(uri);
        if (uris.isEmpty()) {
          tokenToUris.remove(token);
        }
      }
    }
  }

  Set<String> candidateUris(final String token) {
    return tokenToUris.getOrDefault(token, Set.of());
  }

  private void indexRoot(final Path root, final boolean synced) {
    try (final var stream = Files.walk(root)) {
      final List<Path> files = stream.filter(FileUtil::isJavaFile).toList();
      files.forEach(this::indexFromDisk);
      if (!synced) {
        files.forEach(diskMtimes::remove);
      }
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[candidate-index] failed to walk: %s".formatted(root));
    }
  }

  // The file's tokens as on disk; a file that is gone leaves the index. The mtime is read before
  // the content, so a write landing in between leaves an mtime older than the file's and the next
  // sync reads it again.
  void indexFromDisk(final Path path) {
    final String uri = LatheUri.fromPath(path);
    remove(uri);
    try {
      final long mtime = Files.getLastModifiedTime(path).toMillis();
      index(uri, Files.readString(path));
      diskMtimes.put(path, mtime);
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[candidate-index] skipped: %s".formatted(path));
    } catch (final RuntimeException e) {
      // A text-scan bug on one file must cost that file's candidates, not the workspace load
      // that builds this index before the type index.
      LOG.log(Level.SEVERE, e, () -> "[candidate-index] failed to index: %s".formatted(path));
    }
  }

  private static Set<String> extractTokens(final String content) {
    final var tokens = new HashSet<String>();

    // 1. Extract all simple Java identifiers
    int i = 0;
    final int len = content.length();
    while (i < len) {
      if (Character.isJavaIdentifierStart(content.charAt(i))) {
        final int start = i;
        do {
          i++;
        } while (i < len && Character.isJavaIdentifierPart(content.charAt(i)));
        tokens.add(content.substring(start, i));
      } else {
        i++;
      }
    }

    // 2. Extract fully qualified imports (e.g. java.util.List, java.util.concurrent.*)
    int importIdx = content.indexOf("import ");
    while (importIdx >= 0) {
      final int endSemi = content.indexOf(';', importIdx);
      if (endSemi > importIdx && endSemi - importIdx < 200) {
        String imp = content.substring(importIdx + 7, endSemi).trim();
        if (imp.startsWith("static ")) {
          imp = imp.substring(7).trim();
        }
        imp = imp.replace(" ", "").replace("\t", "").replace("\n", "").replace("\r", "");
        if (!imp.isEmpty()) {
          tokens.add(imp);
          addEnclosingTypeToken(tokens, imp);
        }
      }
      importIdx = content.indexOf("import ", importIdx + 7);
    }

    return Set.copyOf(tokens);
  }

  // A nested-type or static-member import (`import pkg.Outer.Inner`) also references the enclosing
  // type `pkg.Outer`, but never spells it on its own — so candidate discovery, which keys on import
  // spellings, would miss this file when searching for `pkg.Outer`. Index the enclosing spelling
  // too, gated on the uppercase-type convention so ordinary imports' package prefixes (`java.util`
  // from `java.util.List`) are not indexed.
  private static void addEnclosingTypeToken(final Set<String> tokens, final String imp) {
    if (imp.endsWith(".*")) {
      return;
    }

    final int lastDot = imp.lastIndexOf('.');
    if (lastDot <= 0) {
      return;
    }

    final String enclosing = imp.substring(0, lastDot);
    // The scan also matches "import" in comments, so the text may end in a dot ("decl..."): no
    // simple name follows, and nothing is indexed.
    final int simpleNameStart = enclosing.lastIndexOf('.') + 1;
    if (simpleNameStart < enclosing.length()
        && Character.isUpperCase(enclosing.charAt(simpleNameStart))) {
      tokens.add(enclosing);
    }
  }
}
