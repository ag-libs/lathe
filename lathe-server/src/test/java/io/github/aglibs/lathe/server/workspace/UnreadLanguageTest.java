package io.github.aglibs.lathe.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UnreadLanguageTest {

  @Test
  void ofSourcePath_kotlinSourceDirectory_returnsKotlin() {
    assertThat(UnreadLanguage.ofSourcePath(Path.of("suite/src/test/kotlin/com/example/A.java")))
        .contains(UnreadLanguage.KOTLIN);
  }

  @Test
  void ofSourcePath_groovySourceDirectory_returnsGroovy() {
    assertThat(UnreadLanguage.ofSourcePath(Path.of("src/main/groovy/A.java")))
        .contains(UnreadLanguage.GROOVY);
  }

  @Test
  void ofSourcePath_javaSourceDirectory_returnsEmpty() {
    assertThat(UnreadLanguage.ofSourcePath(Path.of("suite/src/main/java/com/kotlin/A.java")))
        .isEmpty();
  }

  @Test
  void ofSourcePath_kotlinOutsideSourceTree_returnsEmpty() {
    assertThat(UnreadLanguage.ofSourcePath(Path.of("kotlin/main/A.java"))).isEmpty();
  }
}
