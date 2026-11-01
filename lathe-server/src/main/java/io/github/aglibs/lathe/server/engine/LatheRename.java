package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.List;

/** The result of an applied rename: the new name, the total edits made, and the files touched. */
public record LatheRename(String newName, int totalEdits, List<LatheFileEdit> files) {

  public LatheRename {
    ValidCheck.check().notNull(newName, "newName").notNull(files, "files").validate();
    files = List.copyOf(files);
  }

  // Where the touched files are now: a type rename moved its file, so the asked-about path is gone.
  public List<Path> paths() {
    return files.stream().map(LatheFileEdit::uri).map(LatheUri::toPath).toList();
  }
}
