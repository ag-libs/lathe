package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;
import javax.lang.model.type.TypeMirror;

sealed interface ExpectedValue
    permits ExpectedValue.Unknown, ExpectedValue.Type, ExpectedValue.NoSlot {

  record Unknown() implements ExpectedValue {}

  record Type(TypeMirror type) implements ExpectedValue {

    public Type {
      ValidCheck.requireNotNull(type, "type");
    }
  }

  record NoSlot() implements ExpectedValue {}
}
