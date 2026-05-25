package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;
import javax.lang.model.type.TypeMirror;

record ResolvedReceiver(TypeMirror type, boolean staticAccess) {

  ResolvedReceiver {
    ValidCheck.check().notNull(type, "type").validate();
  }
}
