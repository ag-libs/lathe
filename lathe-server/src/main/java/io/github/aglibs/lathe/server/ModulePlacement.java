package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * Where a source path sits in the reactor: its module rel-path, and whether it is a test source.
 */
public record ModulePlacement(String moduleRel, boolean test) {

  public ModulePlacement {
    ValidCheck.check().notNull(moduleRel, "moduleRel").validate();
  }
}
