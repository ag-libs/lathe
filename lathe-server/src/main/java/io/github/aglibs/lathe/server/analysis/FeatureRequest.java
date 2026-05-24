package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Position;

public record FeatureRequest(
    String uri, String content, Position pos, List<Path> sourceRoots, WorkspaceManifest manifest) {

  public FeatureRequest {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(content, "content")
        .notNull(pos, "pos")
        .notNull(sourceRoots, "sourceRoots")
        .notNull(manifest, "manifest")
        .validate();
  }
}
