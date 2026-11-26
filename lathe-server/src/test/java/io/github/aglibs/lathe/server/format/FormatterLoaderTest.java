package io.github.aglibs.lathe.server.format;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheLayout;
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
        .hasMessageContaining("run `%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND))
        .hasCauseInstanceOf(IllegalAccessError.class);
    assertThatThrownBy(
            () ->
                FormatterLoader.invoke(
                    FormatterLoaderTest.class.getDeclaredMethod("predatesThisJdk"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not support Java %d".formatted(Runtime.version().feature()))
        .hasCauseInstanceOf(NoSuchFieldError.class);
  }

  @Test
  void construct_formatterFailures_surfaceAsExceptionsNotReflectionWrappers() {
    assertThatThrownBy(
            () ->
                FormatterLoader.construct(
                    Failing.class.getDeclaredConstructor(boolean.class), false))
        .isInstanceOf(IOException.class)
        .hasMessage("syntax error");
    assertThatThrownBy(
            () ->
                FormatterLoader.construct(
                    Failing.class.getDeclaredConstructor(boolean.class), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not support Java %d".formatted(Runtime.version().feature()))
        .hasCauseInstanceOf(NoSuchFieldError.class);
  }

  static void failsToParse() throws IOException {
    throw new IOException("syntax error");
  }

  static void predatesThisJdk() {
    throw new NoSuchFieldError("JCCompilationUnit.endPositions");
  }

  static void lacksJavacGrants() {
    throw new IllegalAccessError("jdk.compiler does not export com.sun.tools.javac.util");
  }

  // A formatter class whose constructor fails to parse, or links against a javac field this JDK
  // lacks.
  static final class Failing {

    Failing(final boolean linkage) throws IOException {
      if (linkage) {
        throw new NoSuchFieldError("JCCompilationUnit.endPositions");
      }

      throw new IOException("syntax error");
    }
  }
}
