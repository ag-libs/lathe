package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

// One use of a symbol, and whether it reads or writes it: a field's or variable's assignment,
// compound assignment or increment writes it, as does a call of its generated setter. A call or a
// type use is neither.
public record LatheReference(LatheLocation location, Access access) {

  public enum Access {
    READ,
    WRITE,
    OTHER
  }

  public LatheReference {
    ValidCheck.check().notNull(location, "location").notNull(access, "access").validate();
  }
}
