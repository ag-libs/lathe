package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSessionTest {

  @TempDir private Path tmp;

  private Path sourceFile;
  private ModuleSourceConfig config;
  private Path outputDir;

  @BeforeEach
  void setUp() throws IOException {
    final var sourceRoot = tmp.resolve("module/src/main/java");
    sourceFile = sourceRoot.resolve("com/example/Foo.java");
    config = config(sourceRoot);
    outputDir = config.latheClassesDir().resolve("com/example");
    Files.createDirectories(outputDir);
  }

  @Test
  void deleteClassOutputs_javaSource_removesTopLevelAndNestedClassFiles() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    Files.writeString(outputDir.resolve("Foo$1.class"), "");
    Files.writeString(outputDir.resolve("Foobar.class"), "");
    Files.writeString(outputDir.resolve("Bar.class"), "");

    final int deleted = WorkspaceSession.deleteClassOutputs(config, sourceFile);

    assertThat(deleted).isEqualTo(3);
    assertThat(outputDir.resolve("Foo.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo$Inner.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo$1.class")).doesNotExist();
    assertThat(outputDir.resolve("Foobar.class")).exists();
    assertThat(outputDir.resolve("Bar.class")).exists();
  }

  @Test
  void deleteClassOutputs_nonJavaSource_removesNothing() throws Exception {
    final var txtFile = tmp.resolve("module/src/main/java/com/example/Foo.txt");
    Files.writeString(outputDir.resolve("Foo.class"), "");

    final int deleted = WorkspaceSession.deleteClassOutputs(config, txtFile);

    assertThat(deleted).isZero();
    assertThat(outputDir.resolve("Foo.class")).exists();
  }

  @Test
  void deleteStaleClassOutputs_namedInnerClassRemoved_deletesStaleClassFile() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");

    final int deleted =
        WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Foo$Inner.class")).doesNotExist();
  }

  @Test
  void deleteStaleClassOutputs_anonymousClassRemoved_deletesStaleClassFile() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$1.class"), "");

    final int deleted =
        WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Foo$1.class")).doesNotExist();
  }

  @Test
  void deleteStaleClassOutputs_outerClass_isUntouched() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");

    final int deleted =
        WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isZero();
    assertThat(outputDir.resolve("Foo.class")).exists();
  }

  @Test
  void deleteStaleClassOutputs_sibling_isUntouched() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    Files.writeString(outputDir.resolve("Bar.class"), "");

    WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(outputDir.resolve("Bar.class")).exists();
  }

  // GAP: package-private sibling types (e.g. `class Helper {}` co-declared in Foo.java) produce
  // Helper.class with no Foo$ prefix; deleteStaleClassOutputs only considers Foo$* files and
  // cannot identify Helper.class as stale without sidecar tracking.
  @Disabled
  @Test
  void deleteStaleClassOutputs_packagePrivateSiblingRemoved_deletesStaleClassFile()
      throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Helper.class"), "");

    final int deleted =
        WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Helper.class")).doesNotExist();
  }

  private ModuleSourceConfig config(final Path sourceRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"), tmp.resolve("module/target/classes"), sourceRoot);
  }
}
