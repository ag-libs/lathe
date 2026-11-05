package io.github.aglibs.lathe.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;

record ImportEdit(String qualifiedName, boolean isStatic) {

  ImportEdit {
    ValidCheck.require().notBlank(qualifiedName, "qualifiedName");
  }
}
