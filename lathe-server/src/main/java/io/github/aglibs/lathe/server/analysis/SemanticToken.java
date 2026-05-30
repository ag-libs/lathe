package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Set;

public record SemanticToken(
    int line, int character, int length, String type, Set<String> modifiers) {
  public SemanticToken {
    ValidCheck.check().notNull(type, "type").notNull(modifiers, "modifiers").validate();
  }
}
