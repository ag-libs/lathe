package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.lang.model.element.ElementKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavadocLocatorTest {

  @Test
  void crossFileJavadoc(@TempDir final Path tempDir) throws IOException {
    final var srcDir = tempDir.resolve("src");
    Files.createDirectories(srcDir);
    final var classDir = tempDir.resolve("classes");
    Files.createDirectories(classDir);

    final var documentedSrc = srcDir.resolve("Documented.java");
    Files.writeString(
        documentedSrc,
        """
        /**
         * A well-documented class.
         */
        public class Documented {
            /** The maximum constant. */
            public static final int MAX = 42;

            /**
             * Says hello.
             *
             * @return a greeting
             */
            public String hello() { return "hi"; }
        }
        """);

    TestCompiler.compileToDir(classDir, documentedSrc);

    final var userSrc = tempDir.resolve("User.java");
    Files.writeString(
        userSrc,
        """
        public class User {
            public void run() {
                var d = new Documented();
                int m = Documented.MAX;
                d.hello();
            }
        }
        """);

    final var parsed = TestCompiler.parseWithClasspath(userSrc, classDir);
    final var classElement = parsed.task().getElements().getTypeElement("Documented");
    assertThat(parsed.trees().getPath(classElement)).isNull();

    final var fieldElement =
        classElement.getEnclosedElements().stream()
            .filter(e -> e.getKind() == ElementKind.FIELD)
            .findFirst()
            .orElseThrow();
    final var methodElement =
        classElement.getEnclosedElements().stream()
            .filter(e -> e.getKind() == ElementKind.METHOD)
            .findFirst()
            .orElseThrow();

    final var locator = new JavadocLocator(parsed.parser());

    final var classDoc = locator.locate(classElement, parsed.trees(), List.of(srcDir));
    assertThat(classDoc).isPresent();
    assertThat(classDoc.get()).contains("well-documented class");

    final var fieldDoc = locator.locate(fieldElement, parsed.trees(), List.of(srcDir));
    assertThat(fieldDoc).isPresent();
    assertThat(fieldDoc.get()).contains("maximum constant");

    final var methodDoc = locator.locate(methodElement, parsed.trees(), List.of(srcDir));
    assertThat(methodDoc).isPresent();
    assertThat(methodDoc.get()).contains("Says hello");
  }
}
