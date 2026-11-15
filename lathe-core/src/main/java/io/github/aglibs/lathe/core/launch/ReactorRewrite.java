package io.github.aglibs.lathe.core.launch;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Turns a reactor module's build output (a directory or packaged jar the front-end listed in {@code
 * workspace.json}) into that module's {@code .lathe/} mirror. Any other path passes through.
 */
public final class ReactorRewrite {

  private static final ReactorRewrite NONE = new ReactorRewrite(Map.of());

  // An output with every mirror it stands for, in the order listed: a Gradle jar that packs a
  // second source set's classes stands for both trees.
  private final Map<Path, List<Path>> allMirrors;
  private final Map<Path, Path> firstMirrors;

  private ReactorRewrite(final Map<Path, List<Path>> allMirrors) {
    this.allMirrors = allMirrors;
    this.firstMirrors =
        allMirrors.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, mirrors -> mirrors.getValue().get(0)));
  }

  public static ReactorRewrite of(final Path workspaceRoot, final List<OutputData> outputs) {
    final Path root = workspaceRoot.normalize();
    final Path latheDir = root.resolve(LatheLayout.LATHE_DIR);
    final var mirrors = new LinkedHashMap<Path, List<Path>>();
    for (final OutputData output : outputs) {
      final Path mirror = latheDir.resolve(output.module()).resolve(output.tree()).normalize();
      final List<Path> listed =
          mirrors.computeIfAbsent(
              root.resolve(output.path()).normalize(), path -> new ArrayList<>());
      if (!listed.contains(mirror)) {
        listed.add(mirror);
      }
    }

    return new ReactorRewrite(
        mirrors.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, listed -> List.copyOf(listed.getValue()))));
  }

  public static ReactorRewrite none() {
    return NONE;
  }

  // Each reactor build output with the (first) mirror it stands for.
  public Map<Path, Path> mirrors() {
    return firstMirrors;
  }

  // Every mirror the path stands for, or the path itself when it is not a listed output.
  public List<Path> expand(final Path path) {
    return allMirrors.getOrDefault(path.normalize(), List.of(path));
  }

  public Path toLathe(final Path path) {
    return firstMirrors.getOrDefault(path.normalize(), path);
  }

  public String toLathe(final String path) {
    return Optional.ofNullable(firstMirrors.get(Path.of(path).normalize()))
        .map(Path::toString)
        .orElse(path);
  }

  // A class or module path: an output standing for several mirrors takes all of them, in its place.
  public List<String> toLathe(final List<String> paths) {
    return paths.stream().flatMap(path -> expandString(path).stream()).toList();
  }

  private List<String> expandString(final String path) {
    return Optional.ofNullable(allMirrors.get(Path.of(path).normalize()))
        .map(mirrors -> mirrors.stream().map(Path::toString).toList())
        .orElse(List.of(path));
  }

  public Map<String, String> toLathe(final Map<String, String> paths) {
    return Collections.unmodifiableMap(
        paths.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> toLathe(entry.getValue()),
                    (first, ignored) -> first,
                    LinkedHashMap::new)));
  }
}
