package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionLocatorTest extends SampleFixture {

  private static final String GREETER_SOURCE =
      """
      public class Greeter {
        public static final String DEFAULT_NAME = "world";

        public void greet() {}
      }
      """;

  private static final String ENUM_ARGUMENT_SOURCE =
      """
      import java.util.HashMap;
      import java.util.Map;

      public class EnumArgument {
        enum Status {
          ACTIVE,
          INACTIVE
        }

        public Status getStatus() {
          final Map<String, Status> statuses = new HashMap<>();
          statuses.put("string", Status.ACTIVE);
          return statuses.get("string");
        }
      }
      """;

  @Nested
  class SameFile {

    @Test
    void typeReference() {
      // "Status" in return type of getStatus()
      final var location = definitionAt("Status getStatus", "Status");

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // "Status" name at 1-based line 23 col 8 = 0-based line 22 col 7
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(22);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(7);
    }

    @Test
    void methodReference() {
      // "overloaded" call at line 75 (0-based), col 4
      final var location = definitionAt(75, 4);

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // "overloaded" name at 1-based line 63 col 17 = 0-based line 62 col 16
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(62);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(16);
    }

    @Test
    void enumConstantArgumentInMethodCall(@TempDir final Path tempDir) throws IOException {
      final var sourceFile = tempDir.resolve("EnumArgument.java");
      Files.writeString(sourceFile, ENUM_ARGUMENT_SOURCE);
      final var classDir = tempDir.resolve("classes");
      Files.createDirectories(classDir);
      final var compiled = TestCompiler.parseWithClasspath(sourceFile, classDir);
      final var expression = "statuses.put(\"string\", Status.ACTIVE)";
      final var typePath = pathAt(compiled.trees(), compiled.cu(), expression, "Status");
      final var constantPath = pathAt(compiled.trees(), compiled.cu(), expression, "ACTIVE");
      final var typeLocation = definitionAt(compiled, typePath);
      final var constantLocation = definitionAt(compiled, constantPath);
      final var typeParameter = SourceLocator.parameterElementAt(compiled.trees(), typePath);
      final var constantParameter =
          SourceLocator.parameterElementAt(compiled.trees(), constantPath);

      assertThat(typeLocation).isPresent();
      assertThat(typeLocation.get().getUri()).endsWith("EnumArgument.java");
      assertThat(typeLocation.get().getRange().getStart().getLine()).isEqualTo(4);
      assertThat(typeLocation.get().getRange().getStart().getCharacter()).isEqualTo(7);
      assertThat(constantLocation).isPresent();
      assertThat(constantLocation.get().getUri()).endsWith("EnumArgument.java");
      assertThat(constantLocation.get().getRange().getStart().getLine()).isEqualTo(5);
      assertThat(constantLocation.get().getRange().getStart().getCharacter()).isEqualTo(4);
      assertThat(typeParameter).isNull();
      assertThat(constantParameter).isNull();
    }
  }

  @Nested
  class ReactorFallback {

    @Test
    void findSourceFile_locatesByPackagePath(@TempDir final Path tempDir) throws IOException {
      final var pkgDir = tempDir.resolve("src/com/example");
      Files.createDirectories(pkgDir);
      final var greeterSrc = pkgDir.resolve("Greeter.java");
      Files.writeString(greeterSrc, "package com.example;\npublic class Greeter {}");

      final var classDir = tempDir.resolve("classes");
      Files.createDirectories(classDir);
      TestCompiler.compileToDir(greeterSrc, classDir);

      final var userSrc = tempDir.resolve("User.java");
      Files.writeString(userSrc, "import com.example.Greeter; public class User { Greeter g; }");

      final var compiled = TestCompiler.parseWithClasspath(userSrc, classDir);
      final var greeterElement =
          compiled.task().getElements().getTypeElement("com.example.Greeter");

      final var location =
          DefinitionLocator.locate(
              greeterElement, compiled.trees(), List.of(tempDir.resolve("src")), "file:///x");

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Greeter.java");
    }

    @Test
    void findsFileByTopLevelClassName(@TempDir final Path tempDir) throws IOException {
      final var srcDir = tempDir.resolve("src");
      Files.createDirectories(srcDir);
      final var greeterSrc = srcDir.resolve("Greeter.java");
      Files.writeString(greeterSrc, GREETER_SOURCE);

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
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(13);
    }

    @Test
    void findsMethodPositionInDifferentSourceFile(@TempDir final Path tempDir) throws IOException {
      final var location = locateGreeterMember(tempDir, "greet");

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Greeter.java");
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(3);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(14);
    }

    @Test
    void findsFieldPositionInDifferentSourceFile(@TempDir final Path tempDir) throws IOException {
      final var location = locateGreeterMember(tempDir, "DEFAULT_NAME");

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Greeter.java");
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(1);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(29);
    }
  }

  private static Optional<Location> locateGreeterMember(final Path tempDir, final String memberName)
      throws IOException {
    final var srcDir = tempDir.resolve("src");
    Files.createDirectories(srcDir);
    final var greeterSrc = srcDir.resolve("Greeter.java");
    Files.writeString(greeterSrc, GREETER_SOURCE);

    final var classDir = tempDir.resolve("classes");
    Files.createDirectories(classDir);
    TestCompiler.compileToDir(greeterSrc, classDir);

    final var userSrc = tempDir.resolve("User.java");
    Files.writeString(userSrc, "public class User { public void run(Greeter g) { g.greet(); } }");

    final var compiled = TestCompiler.parseWithClasspath(userSrc, classDir);
    final var greeterElement = compiled.task().getElements().getTypeElement("Greeter");
    final var member =
        greeterElement.getEnclosedElements().stream()
            .filter(e -> e.getSimpleName().contentEquals(memberName))
            .findFirst()
            .orElseThrow();
    assertThat(compiled.trees().getPath(member)).isNull();

    return DefinitionLocator.locate(
        member, compiled.trees(), List.of(srcDir), "file:///irrelevant");
  }

  private Optional<Location> definitionAt(final String expression, final String token) {
    return definitionAt(pathAt(expression, token));
  }

  private Optional<Location> definitionAt(final int line, final int character) {
    return definitionAt(pathAt(line, character));
  }

  private Optional<Location> definitionAt(final TreePath path) {
    final var element = SourceLocator.elementAt(trees, path);
    return DefinitionLocator.locate(
        element, trees, List.of(), cu.getSourceFile().toUri().toString());
  }

  private static Optional<Location> definitionAt(
      final TestCompiler.CrossFileTask compiled, final TreePath path) {
    final var element = SourceLocator.elementAt(compiled.trees(), path);
    return DefinitionLocator.locate(
        element, compiled.trees(), List.of(), compiled.cu().getSourceFile().toUri().toString());
  }

  private TreePath pathAt(final String expression, final String token) {
    return pathAt(trees, cu, expression, token);
  }

  private static TreePath pathAt(
      final Trees trees,
      final CompilationUnitTree cu,
      final String expression,
      final String token) {
    final String source = sourceContent(cu);
    final int expressionOffset = source.indexOf(expression);
    final int offset = source.indexOf(token, expressionOffset);
    return SourceLocator.pathAt(trees, cu, offset);
  }

  private static String sourceContent(final CompilationUnitTree cu) {
    try {
      return cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }
}
