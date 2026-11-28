package io.github.aglibs.lathe.core.schema;

import java.util.Arrays;
import java.util.Optional;

// The JVM languages besides Java a module's sources may hold, which Lathe does not read, in the
// order answers name them. A front-end records them by key, as Gradle names a source set's
// directory set (`kotlin`, `groovy`, `scala`), and finds them in a Maven source root by extension.
public enum OtherLanguage {
  KOTLIN("kotlin", "Kotlin", ".kt"),
  GROOVY("groovy", "Groovy", ".groovy"),
  SCALA("scala", "Scala", ".scala");

  private final String key;
  private final String displayName;
  private final String extension;

  OtherLanguage(final String key, final String displayName, final String extension) {
    this.key = key;
    this.displayName = displayName;
    this.extension = extension;
  }

  public String key() {
    return key;
  }

  public String displayName() {
    return displayName;
  }

  public static Optional<OtherLanguage> ofKey(final String key) {
    return Arrays.stream(values()).filter(language -> language.key.equals(key)).findFirst();
  }

  public static Optional<OtherLanguage> ofFileName(final String fileName) {
    return Arrays.stream(values())
        .filter(language -> fileName.endsWith(language.extension))
        .findFirst();
  }
}
