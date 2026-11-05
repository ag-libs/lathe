package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.List;
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
}
