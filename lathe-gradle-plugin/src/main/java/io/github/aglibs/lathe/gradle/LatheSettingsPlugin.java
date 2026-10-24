package io.github.aglibs.lathe.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

// Registered in settings.gradle(.kts) or by an init script.
public class LatheSettingsPlugin implements Plugin<Settings> {

  @Override
  public void apply(final Settings settings) {}
}
