package io.github.aglibs.lathe.gradle;

import java.io.File;
import java.util.List;
import org.gradle.api.file.FileCollection;

// What Lathe records of a classpath: its files' paths.
final class GradleFiles {

  private GradleFiles() {}

  static List<String> paths(final FileCollection files) {
    return files.getFiles().stream().map(File::getPath).toList();
  }
}
