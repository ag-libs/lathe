package io.github.aglibs.lathe.server.format;

import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.Optional;

// The file a formatter formats and what a command formatter needs to know about it, read from the
// session on the event loop before the format runs off it: the module that holds the file (".",
// the reactor root's, when no module does) and the build tool the last build ran, if one did.
public record FormatTarget(Path file, String module, Path buildTool) {

  public FormatTarget {
    ValidCheck.check().notNull(file, "file").notBlank(module, "module").validate();
  }

  Optional<Path> recordedBuildTool() {
    return Optional.ofNullable(buildTool);
  }
}
