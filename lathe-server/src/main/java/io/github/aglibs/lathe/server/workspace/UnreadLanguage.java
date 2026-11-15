package io.github.aglibs.lathe.server.workspace;

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
}
