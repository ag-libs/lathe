package io.github.aglibs.lathe.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

// A source set beyond main and test, as the facts see it: its tree, and its sources, which decide
// as the facts are written whether the tree exists.
public abstract class SourceSetSources {

  @Input
  public abstract Property<String> getTree();

  @InputFiles
  @IgnoreEmptyDirectories
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getSources();
}
