package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;

// Workspace-relative dirs. outputDir is the real build output (target/classes or test-classes),
// which the server maps to .lathe/ via ReactorRewrite.
public record ResourceRootData(
    String directory, String outputDir, String targetPath, boolean filtering) {

  public ResourceRootData {
    ValidCheck.check()
        .notBlank(directory, "directory")
        .notBlank(outputDir, "outputDir")
        .notNull(targetPath, "targetPath")
        .validate();
  }
}
