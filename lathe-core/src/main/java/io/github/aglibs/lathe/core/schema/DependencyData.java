package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

public record DependencyData(
    String gav,
    String jar,
    SourceStatus status,
    String dir,
    List<String> classpath,
    String typeIndex) {

  public DependencyData {
    ValidCheck.check()
        .notNull(status, "status")
        .notBlank(gav, "gav")
        .notBlank(jar, "jar")
        .when(status == SourceStatus.PRESENT, v -> v.notBlank(dir, "dir"))
        .validate();
  }
}
