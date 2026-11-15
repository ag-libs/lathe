package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Objects;

// One build output that can appear on another module's classpath: a workspace-relative path (an
// output directory or a packaged jar) and the .lathe/ mirror it stands for, the module (moduleRel,
// "" for the root) and its tree. The front-end states it, so nothing downstream guesses from the
// path's shape. shaded: a jar the build shaded, holding relocated classes the mirror lacks; only
// written when true, so the file does not repeat it for every output.
public record OutputData(String path, String module, String tree, Boolean shaded) {

  public OutputData {
    module = Objects.requireNonNullElse(module, "");
    shaded = Boolean.TRUE.equals(shaded) ? Boolean.TRUE : null;
    ValidCheck.check().notBlank(path, "path").notBlank(tree, "tree").validate();
  }

  public OutputData(final String path, final String module, final String tree) {
    this(path, module, tree, null);
  }
}
