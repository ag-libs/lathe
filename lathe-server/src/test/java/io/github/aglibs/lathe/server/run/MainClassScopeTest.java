package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainClassScopeTest {

  private static void mirror(final Path workspaceRoot, final String output) throws IOException {
    final Path classFile =
        workspaceRoot
            .resolve(LatheLayout.LATHE_DIR)
            .resolve("app")
            .resolve(output)
            .resolve("com/example/app/Tester.class");
    Files.createDirectories(classFile.getParent());
    Files.writeString(classFile, "");
  }

  @Test
  void isTestScope_classOnlyInTestOutput_returnsTrue(@TempDir final Path ws) throws IOException {
    mirror(ws, LatheLayout.TEST_CLASSES_DIR);

    assertThat(MainClassScope.isTestScope(ws, "app", "com.example.app.Tester")).isTrue();
  }

  @Test
  void isTestScope_classInMainOutput_returnsFalse(@TempDir final Path ws) throws IOException {
    mirror(ws, LatheLayout.CLASSES_DIR);

    assertThat(MainClassScope.isTestScope(ws, "app", "com.example.app.Tester")).isFalse();
  }

  @Test
  void isTestScope_classInBothOutputs_returnsFalse(@TempDir final Path ws) throws IOException {
    mirror(ws, LatheLayout.CLASSES_DIR);
    mirror(ws, LatheLayout.TEST_CLASSES_DIR);

    assertThat(MainClassScope.isTestScope(ws, "app", "com.example.app.Tester")).isFalse();
  }

  @Test
  void isTestScope_classInNeitherOutput_returnsFalse(@TempDir final Path ws) {
    assertThat(MainClassScope.isTestScope(ws, "app", "com.example.app.Tester")).isFalse();
  }
}
