package io.github.aglibs.lathe.install;

import java.nio.file.Path;
import java.util.List;

// Resolves jars through the build tool that runs sync, against the repositories it was built with.
// Coordinates are groupId:artifactId:version.
public interface ArtifactResolver {

  Path resolve(String coordinates) throws SyncException;

  // The artifact with its runtime dependency closure, resolved as a project's dependency would be.
  List<Path> resolveTransitive(String coordinates) throws SyncException;
}
