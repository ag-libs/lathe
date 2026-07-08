package io.github.aglibs.lathe.core.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TestLaunchDataTest {

  @Test
  void constructor_nullCollections_defaultsToEmpty() {
    final var data = testLaunch(LaunchMode.CLASSPATH, null, null);

    assertThat(data.mode()).isEqualTo(LaunchMode.CLASSPATH);
    assertThat(data.modulePath()).isEmpty();
    assertThat(data.classPath()).isEmpty();
    assertThat(data.patchModules()).isEmpty();
  }

  @Test
  void constructor_mutableCollections_defensivelyCopies() {
    final var classPath = new ArrayList<String>();
    classPath.add("/m2/lib.jar");

    final var data = testLaunch(LaunchMode.CLASSPATH, "", classPath);
    classPath.add("/m2/other.jar");

    assertThat(data.classPath()).containsExactly("/m2/lib.jar");
  }

  @Test
  void constructor_blankKind_throws() {
    assertThatThrownBy(
            () ->
                new TestLaunchData(
                    "1",
                    "",
                    LaunchMode.CLASSPATH,
                    "/jdk",
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .hasMessageContaining("kind");
  }

  @Test
  void constructor_moduleModeBlankMainModule_throws() {
    assertThatThrownBy(() -> testLaunch(LaunchMode.MODULE, "", List.of()))
        .hasMessageContaining("mainModule");
  }

  @Test
  void constructor_classpathModeNonBlankMainModule_throws() {
    assertThatThrownBy(() -> testLaunch(LaunchMode.CLASSPATH, "com.example.app", List.of()))
        .hasMessageContaining("mainModule");
  }

  private static TestLaunchData testLaunch(
      final LaunchMode mode, final String mainModule, final List<String> classPath) {
    return new TestLaunchData(
        "1",
        "surefire",
        mode,
        "/jdk",
        mainModule,
        List.of(),
        classPath,
        Map.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
