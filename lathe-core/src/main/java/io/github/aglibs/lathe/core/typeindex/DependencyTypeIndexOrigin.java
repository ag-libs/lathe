package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;

public record DependencyTypeIndexOrigin(String gav, String jar, long size, long mtimeMillis) {

  public DependencyTypeIndexOrigin {
    ValidCheck.check()
        .notBlank(gav, "gav")
        .notBlank(jar, "jar")
        .isNonNegative(size, "size")
        .isNonNegative(mtimeMillis, "mtimeMillis")
        .validate();
  }
}
