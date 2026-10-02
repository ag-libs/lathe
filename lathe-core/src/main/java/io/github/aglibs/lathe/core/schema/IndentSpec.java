package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;

// As-you-type indentation for a workspace. profile is "google" or "editorconfig"; block and
// continuation are display-column widths (0 means the client derives them).
public record IndentSpec(String profile, int block, int continuation) {

  public IndentSpec {
    ValidCheck.check().notBlank(profile, "profile").validate();
  }
}
