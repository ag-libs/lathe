package io.github.aglibs.lathe.maven.dependency;

import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.artifact.Artifact;

public record DependencySource(
    String gav,
    Path jar,
    SourceStatus status,
    Path dir,
    Artifact sourceArtifact,
    List<Path> classpath,
    Path typeIndex) {

  public DependencySource {
    ValidCheck.check()
        .notNull(status, "status")
        .notBlank(gav, "gav")
        .notNull(jar, "jar")
        .notNull(classpath, "classpath")
        .when(status == SourceStatus.PRESENT, v -> v.notNull(dir, "dir"))
        .validate();
    classpath = List.copyOf(classpath);
  }

  public static DependencySource present(
      final String gav,
      final Path jar,
      final Path dir,
      final Artifact sourceArtifact,
      final List<Path> classpath) {
    return new DependencySource(
        gav, jar, SourceStatus.PRESENT, dir, sourceArtifact, classpath, null);
  }

  public static DependencySource missing(
      final String gav, final Path jar, final List<Path> classpath) {
    return new DependencySource(gav, jar, SourceStatus.MISSING, null, null, classpath, null);
  }

  public static List<DependencySource> withSources(final List<DependencySource> sources) {
    return sources.stream().filter(s -> s.status() == SourceStatus.PRESENT).toList();
  }

  public DependencySource withTypeIndex(final Path typeIndexPath) {
    return new DependencySource(gav, jar, status, dir, sourceArtifact, classpath, typeIndexPath);
  }

  public DependencyData toData() {
    return new DependencyData(
        gav,
        jar != null ? jar.toString() : null,
        status,
        dir != null ? dir.toString() : null,
        classpath.stream().map(Path::toString).toList(),
        typeIndex != null ? typeIndex.toString() : null);
  }
}
