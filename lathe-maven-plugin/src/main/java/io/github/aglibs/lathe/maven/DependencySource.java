package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.ParamStore.PrefixedStore;
import io.github.aglibs.lathe.core.ParamStore.PrefixedWritable;
import io.github.aglibs.lathe.core.maven.DependencyEntry;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.artifact.Artifact;

record DependencySource(
    String gav, Path jar, String status, Path dir, Artifact sourceArtifact, List<Path> classpath)
    implements PrefixedWritable {

  static DependencySource present(
      final String gav,
      final Path jar,
      final Path dir,
      final Artifact sourceArtifact,
      final List<Path> classpath) {
    return new DependencySource(gav, jar, "present", dir, sourceArtifact, classpath);
  }

  static DependencySource missing(final String gav, final Path jar, final List<Path> classpath) {
    return new DependencySource(gav, jar, "missing", null, null, classpath);
  }

  static List<DependencySource> present(final List<DependencySource> sources) {
    return sources.stream().filter(source -> "present".equals(source.status())).toList();
  }

  @Override
  public void writeTo(final PrefixedStore store) {
    new DependencyEntry(
            gav,
            jar != null ? jar.toString() : null,
            status,
            dir != null ? dir.toString() : null,
            classpath.stream().map(Path::toString).toList())
        .writeTo(store);
  }
}
