package io.github.aglibs.lathe.core.launch;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

  private static final ReactorRewrite NONE = new ReactorRewrite(Map.of(), Set.of(), Set.of());

  // An output with every mirror it stands for, in the order listed: a Gradle jar that packs a
  // second source set's classes stands for both trees.
  private final Map<Path, List<Path>> allMirrors;
  private final Map<Path, Path> firstMirrors;
  // Shaded jars: the module's own classes in them name the relocated packages, so a launch runs
  // the jar alone (the String paths, as launch files hold them), while analysis takes the mirror
  // and then the jar on a class path (expand), and the mirror alone where one entry stands for it
  // (toLathe(Path): a module path, which cannot hold both).
  private final Set<Path> keptJars;
  // Jars that also hold classes another compiler (Kotlin, Groovy) wrote, which the mirror of
  // javac's output lacks: a class path, at analysis and at launch alike, takes the mirror and then
  // the jar, so fresh Java classes win and the others still resolve; a module path, the mirror.
  private final Set<Path> extraClassJars;

  private ReactorRewrite(
      final Map<Path, List<Path>> allMirrors,
      final Set<Path> keptJars,
      final Set<Path> extraClassJars) {
    this.allMirrors = allMirrors;
    this.keptJars = keptJars;
    this.extraClassJars = extraClassJars;
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
    final var shaded = new HashSet<Path>();
    final var extraClasses = new HashSet<Path>();
    for (final OutputData output : outputs) {
      final Path path = root.resolve(output.path()).normalize();
      if (Boolean.TRUE.equals(output.shaded())) {
        shaded.add(path);
      }

      if (Boolean.TRUE.equals(output.extraClasses())) {
        extraClasses.add(path);
      }

      final Path mirror = latheDir.resolve(output.module()).resolve(output.tree()).normalize();
      final List<Path> listed = mirrors.computeIfAbsent(path, ignored -> new ArrayList<>());
      if (!listed.contains(mirror)) {
        listed.add(mirror);
      }
    }

    return new ReactorRewrite(
        mirrors.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, listed -> List.copyOf(listed.getValue()))),
        Set.copyOf(shaded),
        Set.copyOf(extraClasses));
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

  // Whether a class path holding this output reads the jar itself, alone or after its mirror: a
  // kept jar, or one with extra classes.
  public boolean readsJar(final Path path) {
    return kept(path) || extraClassJars.contains(path.normalize());
  }

  // Analysis: every mirror the path stands for (a jar it reads itself after them), or the path
  // itself when it is not a listed output.
  public List<Path> expand(final Path path) {
    final Path normal = path.normalize();
    final List<Path> mirrors = allMirrors.getOrDefault(normal, List.of(path));
    return readsJar(normal) ? Stream.concat(mirrors.stream(), Stream.of(normal)).toList() : mirrors;
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
    final Path normal = Path.of(path).normalize();
    final List<Path> mirrors = allMirrors.get(normal);
    if (mirrors == null || kept(normal)) {
      return Stream.of(path);
    }

    final Stream<String> mirrored = mirrors.stream().map(Path::toString);
    return extraClassJars.contains(normal) ? Stream.concat(mirrored, Stream.of(path)) : mirrored;
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
