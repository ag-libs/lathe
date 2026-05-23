package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;

public record JdkTypeIndexOrigin(String javaHome, String vendor, String version) {

  public JdkTypeIndexOrigin {
    ValidCheck.check()
        .notBlank(javaHome, "javaHome")
        .notBlank(vendor, "vendor")
        .notBlank(version, "version")
        .validate();
  }
}
