package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Range;

public record RunTarget(
    String id,
    String parentId,
    RunnableKind kind,
    String label,
    String moduleRel,
    String uri,
    Range range) {

  public RunTarget {
    ValidCheck.check()
        .notBlank(id, "id")
        .notNull(parentId, "parentId")
        .notNull(kind, "kind")
        .notBlank(label, "label")
        .notBlank(moduleRel, "moduleRel")
        .notBlank(uri, "uri")
        .notNull(range, "range")
        .validate();
  }
}
