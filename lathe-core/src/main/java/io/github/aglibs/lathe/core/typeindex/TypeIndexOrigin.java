package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;

public record TypeIndexOrigin(
    TypeIndexOriginKind kind, DependencyTypeIndexOrigin dependency, JdkTypeIndexOrigin jdk) {

  public TypeIndexOrigin {
    ValidCheck.check()
        .notNull(kind, "kind")
        .when(
            kind == TypeIndexOriginKind.DEPENDENCY,
            v -> v.notNull(dependency, "dependency").isNull(jdk, "jdk"))
        .when(
            kind == TypeIndexOriginKind.JDK,
            v -> v.isNull(dependency, "dependency").notNull(jdk, "jdk"))
        .validate();
  }

  public static TypeIndexOrigin dependency(final DependencyTypeIndexOrigin dependency) {
    return new TypeIndexOrigin(TypeIndexOriginKind.DEPENDENCY, dependency, null);
  }

  public static TypeIndexOrigin jdk(final JdkTypeIndexOrigin jdk) {
    return new TypeIndexOrigin(TypeIndexOriginKind.JDK, null, jdk);
  }
}
