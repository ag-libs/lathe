package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.ParamStore.PrefixedStore;
import io.github.aglibs.lathe.core.ParamStore.PrefixedWritable;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.artifact.Artifact;

record DependencySource(String gav, Path jar, String status, Path dir, Artifact sourceArtifact)
    implements PrefixedWritable {

  static DependencySource present(
      final String gav, final Path jar, final Path dir, final Artifact sourceArtifact) {
    return new DependencySource(gav, jar, "present", dir, sourceArtifact);
  }

  static DependencySource missing(final String gav, final Path jar) {
    return new DependencySource(gav, jar, "missing", null, null);
  }

  static List<DependencySource> present(final List<DependencySource> sources) {
    return sources.stream().filter(source -> "present".equals(source.status())).toList();
  }

  @Override
  public void writeTo(final PrefixedStore store) {
    store.set("gav", gav);
    store.set("status", status);
    store.setIfPresent("jar", jar);
    store.setIfPresent("dir", dir);
  }
}
