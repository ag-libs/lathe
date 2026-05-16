package io.github.aglibs.lathe.maven;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class PluginProps {

  static final String GROUP_ID = "io.github.ag-libs";

  private static final Properties PROPS = load();

  private PluginProps() {}

  static String groupId() {
    return GROUP_ID;
  }

  static String version() {
    return PROPS.getProperty("version");
  }

  private static Properties load() {
    final var props = new Properties();
    try (final InputStream in =
        PluginProps.class.getResourceAsStream(
            "/META-INF/maven/%s/lathe-maven-plugin/pom.properties".formatted(GROUP_ID))) {
      if (in != null) {
        props.load(in);
      }
    } catch (final IOException ignored) {
    }
    return props;
  }
}
