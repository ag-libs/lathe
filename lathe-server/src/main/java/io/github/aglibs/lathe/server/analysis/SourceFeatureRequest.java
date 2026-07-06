package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
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
    WorkspaceManifest manifest) {

  public SourceFeatureRequest {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(content, "content")
        .notNull(pos, "pos")
        .notNull(sourceRoots, "sourceRoots")
        .notNull(manifest, "manifest")
        .validate();
    sourceRoots = List.copyOf(sourceRoots);
  }
}
