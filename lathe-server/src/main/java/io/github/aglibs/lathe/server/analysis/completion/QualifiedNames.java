package io.github.aglibs.lathe.server.analysis.completion;

import java.util.Optional;

final class QualifiedNames {

  private QualifiedNames() {}

  static Optional<String> packageName(final String qualifiedName) {
    final int dot = qualifiedName.lastIndexOf('.');
    return dot < 0 ? Optional.empty() : Optional.of(qualifiedName.substring(0, dot));
  }
}
