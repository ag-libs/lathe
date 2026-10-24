package io.github.aglibs.lathe.install;

import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;

// A dependency jar the build tool resolved: its coordinates and the file. The classifier is empty
// for the main jar.
public record ResolvedJar(
    String groupId, String artifactId, String version, String classifier, Path jar) {

  public ResolvedJar {
    ValidCheck.check()
        .notBlank(groupId, "groupId")
        .notBlank(artifactId, "artifactId")
        .notBlank(version, "version")
        .notNull(classifier, "classifier")
        .notNull(jar, "jar")
        .validate();
  }

  public String gav() {
    return "%s:%s:%s".formatted(groupId, artifactId, version);
  }
}
