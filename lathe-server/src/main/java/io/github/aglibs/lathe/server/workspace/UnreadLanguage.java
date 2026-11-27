package io.github.aglibs.lathe.server.workspace;

import java.nio.file.Path;
import java.util.Optional;

// The JVM languages a module may mix in that Lathe does not read, by the directory their Maven and
// Gradle plugins look in by default (src/<source set>/<dir>; Groovy's also holds the Java it
// compiles jointly), in the order answers name them.
public enum UnreadLanguage {
  KOTLIN("Kotlin", "kotlin"),
  GROOVY("Groovy", "groovy");

  private final String displayName;
  private final String sourceDir;

  UnreadLanguage(final String displayName, final String sourceDir) {
    this.displayName = displayName;
    this.sourceDir = sourceDir;
  }

  public String displayName() {
    return displayName;
  }

  String sourceDir() {
    return sourceDir;
  }

  // The language whose default source directory holds the file (src/<source set>/<dir>/…), as a
  // workspace-relative path names it; empty for any other file.
  public static Optional<UnreadLanguage> ofSourcePath(final Path relative) {
    for (int i = 0; i + 2 < relative.getNameCount(); i++) {
      if (!WorkspaceManifest.SOURCES_DIR.equals(relative.getName(i).toString())) {
        continue;
      }

      final String dir = relative.getName(i + 2).toString();
      for (final UnreadLanguage language : values()) {
        if (language.sourceDir.equals(dir)) {
          return Optional.of(language);
        }
      }
    }

    return Optional.empty();
  }
}
