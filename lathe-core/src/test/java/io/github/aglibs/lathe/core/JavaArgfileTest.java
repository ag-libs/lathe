package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaArgfileTest {

  @TempDir Path tmp;

  // The JDK's own launcher is the judge: it must read back exactly the value that was written.
  @Test
  void write_argWithSpaceQuotesBackslashAndHash_javaLauncherReadsItBackIntact() throws Exception {
    final String value = "C:\\Users\\First Last\\a \"b\" 'c' #d";
    final Path file = tmp.resolve("probe.args");
    JavaArgfile.write(
        tmp,
        file,
        List.of("-Dlathe.probe=%s".formatted(value), "-XshowSettings:properties"),
        JavaArgfile.NATIVE_CHARSET);

    final var java =
        Path.of(System.getProperty(LatheLayout.JAVA_HOME_PROPERTY), LatheLayout.BIN_DIR, "java")
            .toString();
    final Process process =
        new ProcessBuilder(java, "@%s".formatted(file), "-version")
            .redirectErrorStream(true)
            .start();
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isZero();
    assertThat(output).contains("lathe.probe = %s".formatted(value));
    assertThat(JavaArgfile.render(List.of("-m", "a/b.C"))).isEqualTo("-m\na/b.C\n");
  }

  @Test
  void write_argOutsideTheCharset_failsNamingTheFile() {
    final Path file = tmp.resolve("jvm.args");

    assertThatThrownBy(
            () ->
                JavaArgfile.write(
                    tmp, file, List.of("-javaagent:/home/Jöran/a.jar"), StandardCharsets.US_ASCII))
        .isInstanceOf(IOException.class)
        .hasMessageContaining(file.toString())
        .hasMessageContaining("US-ASCII");
    assertThat(file).doesNotExist();
  }
}
