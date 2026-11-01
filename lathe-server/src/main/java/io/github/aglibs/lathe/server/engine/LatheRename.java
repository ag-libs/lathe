package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.List;

// The result of an applied rename: the new name, the total edits made, the files touched, and the
// first places the old name survives as text (string literals, qualified in resources), of
// mentionCount in all, which the rename left alone.
public record LatheRename(
    String newName,
    int totalEdits,
    List<LatheFileEdit> files,
    List<LatheLocation> mentions,
    int mentionCount) {

  public LatheRename {
    ValidCheck.check()
        .notNull(newName, "newName")
        .notNull(files, "files")
        .notNull(mentions, "mentions")
        .assertTrue(mentionCount >= mentions.size(), "mentionCount")
        .validate();
    files = List.copyOf(files);
    mentions = List.copyOf(mentions);
  }

  // Where the touched files are now: a type rename moved its file, so the asked-about path is gone.
  public List<Path> paths() {
    return files.stream().map(LatheFileEdit::uri).map(LatheUri::toPath).toList();
  }
}
