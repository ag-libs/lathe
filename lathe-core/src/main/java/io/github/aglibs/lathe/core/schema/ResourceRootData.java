package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Objects;

// Workspace-relative dirs. outputDir is the real build output (target/classes or test-classes),
// which the server maps to .lathe/ via ReactorRewrite. module is the owning reactor module (the
// authoritative moduleRel captured at sync, "." for the root), used as the resource-finder origin.
// It is optional -- defaulted to empty -- so a server upgrade still loads a manifest synced by an
// older plugin (the finder just shows a bare "reactor" origin until the workspace is re-synced),
// rather than failing the whole manifest over one cosmetic field.
public record ResourceRootData(
    String directory, String outputDir, String targetPath, boolean filtering, String module) {

  public ResourceRootData {
    module = Objects.requireNonNullElse(module, "");
    ValidCheck.check()
        .notBlank(directory, "directory")
        .notBlank(outputDir, "outputDir")
        .notNull(targetPath, "targetPath")
        .validate();
  }
}
