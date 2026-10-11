package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.Objects;

// One build output that can appear on another module's classpath: a workspace-relative path (an
// output directory or a packaged jar) and the .lathe/ mirror it stands for, the module (moduleRel,
// "" for the root) and its tree. The front-end states it, so nothing downstream guesses from the
// path's shape.
public record OutputData(String path, String module, String tree) {

  public OutputData {
    module = Objects.requireNonNullElse(module, "");
    ValidCheck.check()
        .notBlank(path, "path")
        .assertTrue(
            LatheLayout.CLASSES_DIR.equals(tree) || LatheLayout.TEST_CLASSES_DIR.equals(tree),
            "tree")
        .validate();
  }
}
