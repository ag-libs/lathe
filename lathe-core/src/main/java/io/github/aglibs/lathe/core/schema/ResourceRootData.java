package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;

// Workspace-relative dirs. outputDir is the real build output (target/classes or test-classes),
// which the server maps to .lathe/ via ReactorRewrite. module is the owning reactor module (the
// authoritative moduleRel captured at sync, "." for the root), used as the resource-finder origin.
public record ResourceRootData(
    String directory, String outputDir, String targetPath, boolean filtering, String module) {

  public ResourceRootData {
    ValidCheck.check()
        .notBlank(directory, "directory")
        .notBlank(outputDir, "outputDir")
        .notNull(targetPath, "targetPath")
        .notBlank(module, "module")
        .validate();
  }
}
