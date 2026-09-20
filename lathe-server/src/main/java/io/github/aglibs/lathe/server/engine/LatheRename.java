package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/** The result of an applied rename: the new name, the total edits made, and the files touched. */
public record LatheRename(String newName, int totalEdits, List<LatheFileEdit> files) {

  public LatheRename {
    ValidCheck.check().notNull(newName, "newName").notNull(files, "files").validate();
    files = List.copyOf(files);
  }
}
