package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionLocatorTest extends SampleFixture {

  @Nested
  class SameFile {

    @Test
    void typeReference() {
      // "Status" in return type of getStatus() at line 101 (0-based), col 9
      final var offset = SourceLocator.toOffset(cu, 101, 9);
      final var path = SourceLocator.pathAt(trees, cu, offset);
      final var element = SourceLocator.elementAt(trees, path);

      final var location =
          DefinitionLocator.locate(
              element, trees, List.of(), cu.getSourceFile().toUri().toString());

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // "Status" name at 1-based line 23 col 8 = 0-based line 22 col 7
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(22);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(7);
    }

    @Test
    void methodReference() {
      // "overloaded" call at line 75 (0-based), col 4
      final var offset = SourceLocator.toOffset(cu, 75, 4);
      final var path = SourceLocator.pathAt(trees, cu, offset);
      final var element = SourceLocator.elementAt(trees, path);

      final var location =
          DefinitionLocator.locate(
              element, trees, List.of(), cu.getSourceFile().toUri().toString());

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // "overloaded" name at 1-based line 63 col 17 = 0-based line 62 col 16
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(62);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(16);
    }
  }

  @Nested
  class ReactorFallback {

    @Test
    void findsFileByTopLevelClassName(@TempDir final Path tempDir) throws IOException {
      final var srcDir = tempDir.resolve("src");
      Files.createDirectories(srcDir);
      final var greeterSrc = srcDir.resolve("Greeter.java");
      Files.writeString(greeterSrc, "public class Greeter { public void greet() {} }");

      final var classDir = tempDir.resolve("classes");
      Files.createDirectories(classDir);
      TestCompiler.compileToDir(greeterSrc, classDir);

      final var userSrc = tempDir.resolve("User.java");
      Files.writeString(userSrc, "public class User { public void run(Greeter g) { g.greet(); } }");

      final var compiled = TestCompiler.parseWithClasspath(userSrc, classDir);
      final var greeterElement = compiled.task().getElements().getTypeElement("Greeter");
      assertThat(compiled.trees().getPath(greeterElement)).isNull();

      final var location =
          DefinitionLocator.locate(
              greeterElement, compiled.trees(), List.of(srcDir), "file:///irrelevant");

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Greeter.java");
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(0);
    }
  }
}
