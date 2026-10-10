package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class FormatterLoaderTest {

  @Test
  void invoke_formatterFailures_surfaceAsExceptionsNotReflectionWrappers() throws Exception {
    assertThatThrownBy(
            () ->
                FormatterLoader.invoke(
                    FormatterLoaderTest.class.getDeclaredMethod("failsToParse"), null))
        .isInstanceOf(IOException.class)
        .hasMessage("syntax error");
    assertThatThrownBy(
            () ->
                FormatterLoader.invoke(
                    FormatterLoaderTest.class.getDeclaredMethod("lacksJavacGrants"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("run lathe:sync")
        .hasCauseInstanceOf(IllegalAccessError.class);
  }

  static void failsToParse() throws IOException {
    throw new IOException("syntax error");
  }

  static void lacksJavacGrants() {
    throw new IllegalAccessError("jdk.compiler does not export com.sun.tools.javac.util");
  }
}
