package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmArgsWriterTest {

  private static final String API = "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED";
  private static final String CODE = "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED";

  @TempDir Path workspaceRoot;

  // Both forms become joined flags; anything that is not an access flag is dropped, and a flag
  // granted twice is written once, in first-seen order.
  @Test
  void write_mixedTokens_writesDedupedAccessFlagsOnly() throws Exception {
    JvmArgsWriter.write(
        workspaceRoot,
        List.of(
            "-Xmx2g",
            "--add-opens",
            CODE,
            "--add-exports=%s".formatted(API),
            "-XDcompilePolicy=simple",
            "--add-opens=%s".formatted(CODE)));

    assertThat(Files.readAllLines(jvmArgs()))
        .containsExactly("--add-opens=%s".formatted(CODE), "--add-exports=%s".formatted(API));
  }

  @Test
  void write_noAccessFlags_deletesStaleFile() throws Exception {
    JvmArgsWriter.write(workspaceRoot, List.of("--add-exports=%s".formatted(API)));
    assertThat(jvmArgs()).exists();

    JvmArgsWriter.write(workspaceRoot, List.of("-Xmx2g", "--add-exports"));

    assertThat(jvmArgs()).doesNotExist();
  }

  private Path jvmArgs() {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.JVM_ARGS_FILE);
  }
}
