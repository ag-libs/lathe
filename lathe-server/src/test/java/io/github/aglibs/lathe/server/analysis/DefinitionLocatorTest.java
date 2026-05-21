package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.TestCompiler;
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
    void fieldReference() {
      // "name" in "return name" on line 39 (1-based) = 0-based line 38, col 11
      final var location = definitionAt(38, 11);

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // "name" field at 1-based line 30 = 0-based line 29, col 23
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(29);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(23);
    }

    @Test
    void staticFieldReference() {
      // "PREFIX" in "s.startsWith(PREFIX)" on line 84 (1-based) = 0-based line 83, col 34
      final var location = definitionAt(83, 34);

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // "PREFIX" field at 1-based line 28 = 0-based line 27, col 30
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(27);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(30);
    }

    @Test
    void secondOverload() {
      // "overloaded(42)" on line 77 (1-based) = 0-based line 76, col 4
      final var location = definitionAt(76, 4);

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // int overload at 1-based line 67 = 0-based line 66, col 13
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(66);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(13);
    }

    @Test
    void genericMethod() {
      // "identity(\"text\")" on line 78 (1-based) = 0-based line 77, col 4
      final var location = definitionAt(77, 4);

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // "identity" at 1-based line 71 = 0-based line 70, col 15
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(70);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(15);
    }

    @Test
    void constructorCall() {
      // "DocHelper" in "new DocHelper()" on line 143 (1-based) = 0-based line 142, col 25
      final var location = definitionAt(142, 25);

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Sample.java");
      // "DocHelper" class at 1-based line 116 = 0-based line 115, col 22
      assertThat(location.get().getRange().getStart().getLine()).isEqualTo(115);
      assertThat(location.get().getRange().getStart().getCharacter()).isEqualTo(22);
    }

    @Test
    void enumConstantArgumentInMethodCall(@TempDir final Path tempDir) throws IOException {
      final var sourceFile = tempDir.resolve("EnumArgument.java");
      Files.writeString(sourceFile, ENUM_ARGUMENT_SOURCE);
      final var classDir = tempDir.resolve("classes");
      Files.createDirectories(classDir);
      final var parsed = TestCompiler.parseWithClasspath(sourceFile, classDir);
      final var expression = "statuses.put(\"string\", Status.ACTIVE)";
      final var typePath = pathAt(parsed.trees(), parsed.cu(), expression, "Status");
      final var constantPath = pathAt(parsed.trees(), parsed.cu(), expression, "ACTIVE");
      final var typeLocation = definitionAt(parsed, typePath);
      final var constantLocation = definitionAt(parsed, constantPath);
      final var typeParameter = SourceLocator.parameterElementAt(parsed.trees(), typePath);
      final var constantParameter = SourceLocator.parameterElementAt(parsed.trees(), constantPath);

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
  class ExternalSymbol {

    @Test
    void staticImportMethod_withoutSourceRoots_isEmpty() {
      // "format" in "return format("result: %s", value)" on line 52 (1-based) = 0-based line 51
      final var location = definitionAt(51, 11);
      assertThat(location).isNotPresent();
    }

    @Test
    void staticImportField_withoutSourceRoots_isEmpty() {
      // "ENGLISH" in "return "hello".toUpperCase(ENGLISH)" on line 56 (1-based) = 0-based line 55
      final var location = definitionAt(55, 31);
      assertThat(location).isNotPresent();
    }

    @Test
    void methodReference_withoutSourceRoots_isEmpty() {
      // "toUpperCase" in ".map(String::toUpperCase)" on line 85 (1-based) = 0-based line 84
      final var location = definitionAt(84, 21);
      assertThat(location).isNotPresent();
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
      TestCompiler.compileToDir(classDir, greeterSrc);

      final var userSrc = tempDir.resolve("User.java");
      Files.writeString(userSrc, "import com.example.Greeter; public class User { Greeter g; }");

      final var parsed = TestCompiler.parseWithClasspath(userSrc, classDir);
      final var greeterElement = parsed.task().getElements().getTypeElement("com.example.Greeter");

      final var location =
          new DefinitionLocator(parsed.parser())
              .locate(greeterElement, parsed.trees(), List.of(tempDir.resolve("src")), "file:///x");

      assertThat(location).isPresent();
      assertThat(location.get().getUri()).endsWith("Greeter.java");
    }

    @Test
    void findSourceFile_locatesByModulePrefixedPath(@TempDir final Path tempDir)
        throws IOException {
      // java.lang.String belongs to java.base — always in the module graph
      final var srcRoot = tempDir.resolve("src");
      Files.createDirectories(srcRoot.resolve("java.base/java/lang"));
      Files.writeString(
          srcRoot.resolve("java.base/java/lang/String.java"),
          "package java.lang;\npublic class String {}");

      final var classDir = tempDir.resolve("classes");
      Files.createDirectories(classDir);
      final var userSrc = tempDir.resolve("User.java");
      Files.writeString(userSrc, "public class User { String s; }");
      final var parsed = TestCompiler.parseWithClasspath(userSrc, classDir);
      final var stringElement = parsed.task().getElements().getTypeElement("java.lang.String");

      final var file = DefinitionLocator.findSourceFile(stringElement, List.of(srcRoot));

      assertThat(file).isPresent();
      assertThat(file.get().getFileName().toString()).isEqualTo("String.java");
    }

    @Test
    void findsFileByTopLevelClassName(@TempDir final Path tempDir) throws IOException {
      final var srcDir = tempDir.resolve("src");
      Files.createDirectories(srcDir);
      final var greeterSrc = srcDir.resolve("Greeter.java");
      Files.writeString(greeterSrc, GREETER_SOURCE);

      final var classDir = tempDir.resolve("classes");
      Files.createDirectories(classDir);
      TestCompiler.compileToDir(classDir, greeterSrc);

      final var userSrc = tempDir.resolve("User.java");
      Files.writeString(userSrc, "public class User { public void run(Greeter g) { g.greet(); } }");

      final var parsed = TestCompiler.parseWithClasspath(userSrc, classDir);
      final var greeterElement = parsed.task().getElements().getTypeElement("Greeter");
      assertThat(parsed.trees().getPath(greeterElement)).isNull();

      final var location =
          new DefinitionLocator(parsed.parser())
              .locate(greeterElement, parsed.trees(), List.of(srcDir), "file:///irrelevant");

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
    TestCompiler.compileToDir(classDir, greeterSrc);

    final var userSrc = tempDir.resolve("User.java");
    Files.writeString(userSrc, "public class User { public void run(Greeter g) { g.greet(); } }");

    final var parsed = TestCompiler.parseWithClasspath(userSrc, classDir);
    final var greeterElement = parsed.task().getElements().getTypeElement("Greeter");
    final var member =
        greeterElement.getEnclosedElements().stream()
            .filter(e -> e.getSimpleName().contentEquals(memberName))
            .findFirst()
            .orElseThrow();
    assertThat(parsed.trees().getPath(member)).isNull();

    return new DefinitionLocator(parsed.parser())
        .locate(member, parsed.trees(), List.of(srcDir), "file:///irrelevant");
  }

  private Optional<Location> definitionAt(final String expression, final String token) {
    return definitionAt(pathAt(expression, token));
  }

  private Optional<Location> definitionAt(final int line, final int character) {
    return definitionAt(pathAt(line, character));
  }

  private Optional<Location> definitionAt(final TreePath path) {
    final var element = SourceLocator.elementAt(compiled.trees(), path);
    return new DefinitionLocator(compiled.parser())
        .locate(
            element, compiled.trees(), List.of(), compiled.cu().getSourceFile().toUri().toString());
  }

  private static Optional<Location> definitionAt(
      final TestCompiler.ParsedSource parsed, final TreePath path) {
    final var element = SourceLocator.elementAt(parsed.trees(), path);
    return new DefinitionLocator(parsed.parser())
        .locate(element, parsed.trees(), List.of(), parsed.cu().getSourceFile().toUri().toString());
  }

}
