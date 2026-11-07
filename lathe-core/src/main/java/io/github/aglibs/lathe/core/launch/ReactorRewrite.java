package io.github.aglibs.lathe.core.launch;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import java.nio.file.Path;
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

  private final Map<Path, Path> mirrors;
  private final Set<Path> kept;

  private ReactorRewrite(final Map<Path, Path> mirrors, final Set<Path> kept) {
    this.mirrors = mirrors;
    this.kept = kept;
  }

  public static ReactorRewrite of(final Path workspaceRoot, final List<OutputData> outputs) {
    final Path root = workspaceRoot.normalize();
    final Path latheDir = root.resolve(LatheLayout.LATHE_DIR);
    return new ReactorRewrite(
        outputs.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    output -> root.resolve(output.path()).normalize(),
                    output -> latheDir.resolve(output.module()).resolve(output.tree()).normalize(),
                    (first, ignored) -> first)),
        Set.of());
  }

  public static ReactorRewrite none() {
    return NONE;
  }

  // Each reactor build output with the mirror it stands for.
  public Map<Path, Path> mirrors() {
    return mirrors;
  }

  // The same, but for one build output, which stays as the build wrote it: a test that finds its
  // sources from where its class file lies needs the build's layout around it.
  public ReactorRewrite keeping(final Path buildOutput) {
    final Path kept = buildOutput.normalize();
    return new ReactorRewrite(
        mirrors.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(kept))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)),
        Stream.concat(this.kept.stream(), Stream.of(kept)).collect(Collectors.toUnmodifiableSet()));
  }

  // The build outputs left as the build wrote them (keeping), as a run means to load them.
  public Set<Path> kept() {
    return kept;
  }

  public Path toLathe(final Path path) {
    return mirrors.getOrDefault(path.normalize(), path);
  }

  public String toLathe(final String path) {
    return Optional.ofNullable(mirrors.get(Path.of(path).normalize()))
        .map(Path::toString)
        .orElse(path);
  }

  public List<String> toLathe(final List<String> paths) {
    return paths.stream().map(this::toLathe).toList();
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
