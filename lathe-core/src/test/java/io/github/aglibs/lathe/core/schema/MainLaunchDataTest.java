package io.github.aglibs.lathe.core.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MainLaunchDataTest {

  @Test
  void constructor_nullCollections_defaultsToEmpty() {
    final var data = mainLaunch(LaunchMode.CLASSPATH, null, null);

    assertThat(data.mode()).isEqualTo(LaunchMode.CLASSPATH);
    assertThat(data.modulePath()).isEmpty();
    assertThat(data.classPath()).isEmpty();
    assertThat(data.jvmArgs()).isEmpty();
  }

  @Test
  void constructor_mutableCollections_defensivelyCopies() {
    final var classPath = new ArrayList<String>();
    classPath.add("/m2/lib.jar");

    final var data = mainLaunch(LaunchMode.CLASSPATH, "", classPath);
    classPath.add("/m2/other.jar");

    assertThat(data.classPath()).containsExactly("/m2/lib.jar");
  }

  @Test
  void constructor_blankJavaHome_throws() {
    assertThatThrownBy(
            () ->
                new MainLaunchData(
                    "1", LaunchMode.CLASSPATH, "", "", null, null, null, null, null, null, null))
        .hasMessageContaining("javaHome");
  }

  @Test
  void constructor_moduleModeBlankMainModule_throws() {
    assertThatThrownBy(() -> mainLaunch(LaunchMode.MODULE, "", List.of()))
        .hasMessageContaining("mainModule");
  }

  @Test
  void constructor_classpathModeNonBlankMainModule_throws() {
    assertThatThrownBy(() -> mainLaunch(LaunchMode.CLASSPATH, "com.example.app", List.of()))
        .hasMessageContaining("mainModule");
  }

  private static MainLaunchData mainLaunch(
      final LaunchMode mode, final String mainModule, final List<String> classPath) {
    return new MainLaunchData(
        "1",
        mode,
        "/jdk",
        mainModule,
        List.of(),
        classPath,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
