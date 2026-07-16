package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class ExtensionProps {

  private static final Properties PROPS = load();

  private ExtensionProps() {}

  static String version() {
    return PROPS.getProperty("version");
  }

  private static Properties load() {
    final var props = new Properties();
    try (final InputStream in =
        ExtensionProps.class.getResourceAsStream(
            "/META-INF/maven/%s/%s/pom.properties"
                .formatted(LatheLayout.LATHE_GROUP_ID, "lathe-maven-extension"))) {
      if (in != null) {
        props.load(in);
      }
    } catch (final IOException ignored) {
    }

    return props;
  }
}
