package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;

public record TypeIndexOrigin(
    TypeIndexOriginKind kind,
    DependencyTypeIndexOrigin dependency,
    JdkTypeIndexOrigin jdk,
    ReactorTypeIndexOrigin reactor) {

  public TypeIndexOrigin {
    ValidCheck.check()
        .notNull(kind, "kind")
        .when(
            kind == TypeIndexOriginKind.DEPENDENCY,
            v -> v.notNull(dependency, "dependency").isNull(jdk, "jdk").isNull(reactor, "reactor"))
        .when(
            kind == TypeIndexOriginKind.JDK,
            v -> v.isNull(dependency, "dependency").notNull(jdk, "jdk").isNull(reactor, "reactor"))
        .when(
            kind == TypeIndexOriginKind.REACTOR,
            v -> v.isNull(dependency, "dependency").isNull(jdk, "jdk").notNull(reactor, "reactor"))
        .validate();
  }

  public static TypeIndexOrigin dependency(final DependencyTypeIndexOrigin dependency) {
    return new TypeIndexOrigin(TypeIndexOriginKind.DEPENDENCY, dependency, null, null);
  }

  public static TypeIndexOrigin jdk(final JdkTypeIndexOrigin jdk) {
    return new TypeIndexOrigin(TypeIndexOriginKind.JDK, null, jdk, null);
  }

  public static TypeIndexOrigin reactor(final ReactorTypeIndexOrigin reactor) {
    return new TypeIndexOrigin(TypeIndexOriginKind.REACTOR, null, null, reactor);
  }
}
