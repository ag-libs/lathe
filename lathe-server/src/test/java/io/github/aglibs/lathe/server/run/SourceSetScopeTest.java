package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceSetScopeTest {

  // A class compiled into a Gradle source set's tree, as a sync leaves it: the tree's params and
  // its mirror.
  private static void mirror(final Path workspaceRoot, final String tree, final String className)
      throws IOException {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve("app");
    final Path classFile = moduleDir.resolve(tree).resolve(className.replace('.', '/') + ".class");
    Files.createDirectories(classFile.getParent());
    Files.writeString(classFile, "");
    Files.writeString(moduleDir.resolve(LatheLayout.paramsFileName(tree)), "{}");
  }

  // Only in integrationTest's tree: refused, naming it. Also in the test tree, or in
  // no tree at all: the module's own launches decide, as before.
  @Test
  void uncapturedReason_classOnlyInASourceSetTree_namesTheSourceSet(@TempDir final Path ws)
      throws IOException {
    mirror(ws, LatheLayout.sourceSetTree("integrationTest"), "com.example.app.BookIT");
    mirror(ws, LatheLayout.sourceSetTree("integrationTest"), "com.example.app.Shared");
    mirror(ws, LatheLayout.TEST_CLASSES_DIR, "com.example.app.Shared");

    assertThat(SourceSetScope.uncapturedReason(ws, "app", "com.example.app.BookIT"))
        .hasValueSatisfying(
            reason -> assertThat(reason).contains("com.example.app.BookIT", "integrationTest"));
    assertThat(SourceSetScope.uncapturedReason(ws, "app", "com.example.app.Shared")).isEmpty();
    assertThat(SourceSetScope.uncapturedReason(ws, "app", "com.example.app.Missing")).isEmpty();
  }
}
