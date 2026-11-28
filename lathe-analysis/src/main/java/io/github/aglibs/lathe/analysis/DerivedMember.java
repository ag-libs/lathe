package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;

// A member a processor generated from a hand-written one, as a reference target, and how a refusal
// names it ("the method `BuilderBase.port(int)`").
public record DerivedMember(ReferenceTarget target, String description) {

  public DerivedMember {
    ValidCheck.check().notNull(target, "target").notBlank(description, "description").validate();
  }
}
