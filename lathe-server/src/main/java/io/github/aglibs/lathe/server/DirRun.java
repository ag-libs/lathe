package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// Run plan for a directory: its module and the selectors to launch. Selector field
// names match the lathe.run.test wire so the client forwards them unchanged. Empty
// selections means nothing runnable resolves here (reactor root or non-test dir).
record DirRun(String moduleRel, List<Selector> selections) {

  DirRun {
    ValidCheck.check().notNull(moduleRel, "moduleRel").notNull(selections, "selections").validate();
    selections = List.copyOf(selections);
  }

  record Selector(String selectorKind, String selectorValue) {
    Selector {
      ValidCheck.check()
          .notBlank(selectorKind, "selectorKind")
          .notBlank(selectorValue, "selectorValue")
          .validate();
    }
  }
}
