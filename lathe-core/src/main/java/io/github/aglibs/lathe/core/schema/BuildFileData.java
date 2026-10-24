package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;

// A workspace-relative build file the server watches. With a fingerprint (a POM) it counts as
// changed when its content differs from the synced one; without (OpenJDK's batch markers, which
// make only touches after a module compiles) whenever it is touched.
public record BuildFileData(String path, ContentFingerprint fingerprint) {

  public BuildFileData {
    ValidCheck.check().notBlank(path, "path").validate();
  }
}
