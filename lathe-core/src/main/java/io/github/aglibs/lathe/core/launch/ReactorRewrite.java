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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns a reactor module's build output (a directory or packaged jar the front-end listed in {@code
 * workspace.json}) into that module's {@code .lathe/} mirror. Any other path passes through.
 */
public final class ReactorRewrite {

  private static final ReactorRewrite NONE = new ReactorRewrite(Map.of(), Set.of());

  // An output with every mirror it stands for, in the order listed: a Gradle jar that packs a
  // second source set's classes stands for both trees.
  private final Map<Path, List<Path>> allMirrors;
  private final Map<Path, Path> firstMirrors;
  // Shaded jars: the module's own classes in them name the relocated packages, so a launch runs
  // the jar alone, while analysis takes the mirror and then the jar for the relocated classes.
  private final Set<Path> keptJars;

  private ReactorRewrite(final Map<Path, List<Path>> allMirrors, final Set<Path> keptJars) {
    this.allMirrors = allMirrors;
    this.keptJars = keptJars;
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
                    Map.Entry::getKey, listed -> List.copyOf(listed.getValue()))),
        Set.of());
  }

  // The same rewrite keeping each of the jars (shaded ones) as described at keptJars.
  public ReactorRewrite keeping(final Set<Path> jars) {
    return new ReactorRewrite(allMirrors, Set.copyOf(jars));
  }

  public static ReactorRewrite none() {
    return NONE;
  }

  // Each reactor build output with the (first) mirror it stands for.
  public Map<Path, Path> mirrors() {
    return firstMirrors;
  }

  // Whether a launch runs this output as itself.
  public boolean kept(final Path path) {
    return keptJars.contains(path.normalize());
  }

  // Analysis: every mirror the path stands for (a kept jar itself after them), or the path itself
  // when it is not a listed output.
  public List<Path> expand(final Path path) {
    final Path normal = path.normalize();
    final List<Path> mirrors = allMirrors.getOrDefault(normal, List.of(path));
    return kept(normal) ? Stream.concat(mirrors.stream(), Stream.of(normal)).toList() : mirrors;
  }

  public Path toLathe(final Path path) {
    return firstMirrors.getOrDefault(path.normalize(), path);
  }

  // A launch's path, as its launch file holds it: a kept jar stays itself.
  public String toLathe(final String path) {
    if (kept(Path.of(path))) {
      return path;
    }

    return Optional.ofNullable(firstMirrors.get(Path.of(path).normalize()))
        .map(Path::toString)
        .orElse(path);
  }

  // A module path: one mirror per entry, as a second directory there would hold no module.
  public List<String> toLathe(final List<String> paths) {
    return paths.stream().map(this::toLathe).toList();
  }

  // A class path: an output standing for several mirrors takes all of them, in its place.
  public List<String> toLatheClassPath(final List<String> paths) {
    return paths.stream().flatMap(this::classPathEntries).toList();
  }

  private Stream<String> classPathEntries(final String path) {
    final List<Path> mirrors = allMirrors.get(Path.of(path).normalize());
    return mirrors == null || kept(Path.of(path))
        ? Stream.of(path)
        : mirrors.stream().map(Path::toString);
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
