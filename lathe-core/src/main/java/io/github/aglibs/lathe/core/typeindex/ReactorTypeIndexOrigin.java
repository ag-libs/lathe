package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;

public record ReactorTypeIndexOrigin(String moduleRel, SourceTree sourceTree, String outputDir) {

  public ReactorTypeIndexOrigin {
    ValidCheck.check()
        .notBlank(moduleRel, "moduleRel")
        .notNull(sourceTree, "sourceTree")
        .notBlank(outputDir, "outputDir")
        .validate();
  }
}
