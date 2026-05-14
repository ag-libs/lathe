package io.github.aglibs.lathe.maven.dependency;

import io.github.aglibs.lathe.core.maven.DependencyEntry;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.artifact.Artifact;

public record DependencySource(
    String gav, Path jar, String status, Path dir, Artifact sourceArtifact, List<Path> classpath) {

  public static DependencySource present(
      final String gav,
      final Path jar,
      final Path dir,
      final Artifact sourceArtifact,
      final List<Path> classpath) {
    return new DependencySource(gav, jar, "present", dir, sourceArtifact, classpath);
  }

  public static DependencySource missing(
      final String gav, final Path jar, final List<Path> classpath) {
    return new DependencySource(gav, jar, "missing", null, null, classpath);
  }

  public static List<DependencySource> present(final List<DependencySource> sources) {
    return sources.stream().filter(source -> "present".equals(source.status())).toList();
  }

  public DependencyEntry toEntry() {
    return new DependencyEntry(
        gav,
        jar != null ? jar.toString() : null,
        status,
        dir != null ? dir.toString() : null,
        classpath.stream().map(Path::toString).toList());
  }
}
