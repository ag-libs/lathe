package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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

  @Disabled
  @Test
  void deleteStaleClassOutputs_namedInnerClassRemoved_deletesStaleClassFile() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    setOld(outputDir.resolve("Foo$Inner.class"));

    final int deleted = WorkspaceSession.deleteStaleClassOutputs(config, sourceFile);

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Foo$Inner.class")).doesNotExist();
  }

  @Disabled
  @Test
  void deleteStaleClassOutputs_anonymousClassRemoved_deletesStaleClassFile() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$1.class"), "");
    setOld(outputDir.resolve("Foo$1.class"));

    final int deleted = WorkspaceSession.deleteStaleClassOutputs(config, sourceFile);

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Foo$1.class")).doesNotExist();
  }

  @Disabled
  @Test
  void deleteStaleClassOutputs_outerClass_isUntouched() throws Exception {
    // Foo.class is the reference timestamp — it must never be deleted even though it matches.
    Files.writeString(outputDir.resolve("Foo.class"), "");

    final int deleted = WorkspaceSession.deleteStaleClassOutputs(config, sourceFile);

    assertThat(deleted).isZero();
    assertThat(outputDir.resolve("Foo.class")).exists();
  }

  @Disabled
  @Test
  void deleteStaleClassOutputs_sibling_isUntouched() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    Files.writeString(outputDir.resolve("Bar.class"), "");
    setOld(outputDir.resolve("Foo$Inner.class"));
    setOld(outputDir.resolve("Bar.class"));

    WorkspaceSession.deleteStaleClassOutputs(config, sourceFile);

    assertThat(outputDir.resolve("Bar.class")).exists();
  }

  // GAP: package-private sibling types (e.g. `class Helper {}` co-declared in Foo.java) produce
  // Helper.class with no Foo prefix, so the timestamp approach cannot identify them as belonging
  // to Foo.java. Needs sidecar tracking of declared top-level types per source file.
  @Disabled
  @Test
  void deleteStaleClassOutputs_packagePrivateSiblingRemoved_deletesStaleClassFile()
      throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Helper.class"), "");
    setOld(outputDir.resolve("Helper.class"));

    final int deleted = WorkspaceSession.deleteStaleClassOutputs(config, sourceFile);

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Helper.class")).doesNotExist();
  }

  private static void setOld(final Path path) throws IOException {
    Files.setLastModifiedTime(path, FileTime.from(Instant.now().minusSeconds(10)));
  }

  private ModuleSourceConfig config(final Path sourceRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"), tmp.resolve("module/target/classes"), sourceRoot);
  }
}
