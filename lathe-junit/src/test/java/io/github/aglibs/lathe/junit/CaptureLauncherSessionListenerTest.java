package io.github.aglibs.lathe.junit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CaptureLauncherSessionListenerTest {

  private static final int JAVA_8_CLASS_FILE = 52;
  private static final int JAVA_17_CLASS_FILE = 61;

  @Test
  void featureVersion_oldAndNewSchemes_readTheFeatureRelease() {
    assertThat(CaptureLauncherSessionListener.featureVersion("1.8")).isEqualTo(8);
    assertThat(CaptureLauncherSessionListener.featureVersion("11"))
        .isEqualTo(11)
        .isLessThan(LatheLayout.MIN_JAVA_VERSION);
    assertThat(CaptureLauncherSessionListener.featureVersion("17"))
        .isEqualTo(LatheLayout.MIN_JAVA_VERSION);
    assertThat(CaptureLauncherSessionListener.featureVersion("25")).isEqualTo(25);
  }

  // A test JVM older than Lathe's code loads the classes JUnit finds through their service files;
  // only the capture they lead to is Java 17.
  @Test
  void classFileVersion_entryPointsAndCapture_entryPointsLoadOnJava8() {
    assertThat(classFileVersion(CaptureLauncherSessionListener.class)).isEqualTo(JAVA_8_CLASS_FILE);
    assertThat(classFileVersion(CaptureOnlyPostDiscoveryFilter.class)).isEqualTo(JAVA_8_CLASS_FILE);
    assertThat(classFileVersion(LaunchCapture.class)).isEqualTo(JAVA_17_CLASS_FILE);
  }

  // The major version follows the 4-byte magic and the 2-byte minor version.
  private static int classFileVersion(final Class<?> type) {
    try (final InputStream in =
        type.getResourceAsStream("%s.class".formatted(type.getSimpleName()))) {
      return ByteBuffer.wrap(in.readNBytes(8)).getShort(6);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
