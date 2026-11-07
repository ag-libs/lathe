package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Position;

public record SourceFeatureRequest(
    String uri,
    String content,
    int version,
    Position pos,
    List<Path> sourceRoots,
    LibrarySources libraries) {

  public SourceFeatureRequest {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(content, "content")
        .notNull(pos, "pos")
        .notNull(sourceRoots, "sourceRoots")
        .notNull(libraries, "libraries")
        .validate();
    sourceRoots = List.copyOf(sourceRoots);
  }

  // The request's source roots and the dependencies' extracted sources: where a declaration's
  // source may be found.
  public List<Path> allSourceRoots() {
    return Stream.concat(sourceRoots.stream(), libraries.externalSourceDirs().stream()).toList();
  }

  // The request's source roots and the dependencies' and JDK's sources: where a type's declaration
  // may be found.
  public List<Path> typeSourceRoots() {
    return Stream.of(
            sourceRoots.stream(),
            libraries.dependencySourceDirs().stream(),
            libraries.jdkModuleSourceDirs().stream())
        .flatMap(stream -> stream)
        .toList();
  }
}
