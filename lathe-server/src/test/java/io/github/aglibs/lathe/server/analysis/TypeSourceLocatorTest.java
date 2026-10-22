package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeSourceLocatorTest {

  @TempDir private Path sourceRoot;

  @Test
  void locate_topLevelType_returnsDeclarationNameRange() throws IOException {
    final var sourceFile =
        write("com/example/Service.java", "package com.example;\nclass Service {}\n");
    final var entry = entry("com.example.Service");

    try (var parser = new SourceParser()) {
      final var location = TypeSourceLocator.locate(entry, List.of(sourceRoot), parser);

      assertThat(location).isPresent();
      assertThat(location.orElseThrow().getUri()).isEqualTo(sourceFile.toUri().toString());
      assertThat(location.orElseThrow().getRange().getStart().getLine()).isEqualTo(1);
      assertThat(location.orElseThrow().getRange().getStart().getCharacter()).isEqualTo(6);
      assertThat(location.orElseThrow().getRange().getEnd().getCharacter()).isEqualTo(13);
    }
  }

  @Test
  void locate_nestedType_returnsNestedDeclaration() throws IOException {
    write(
        "com/example/Outer.java",
        """
        package com.example;
        class Outer {
          interface Inner {}
        }
        """);
    final var entry = entry("com.example.Outer$Inner");

    try (var parser = new SourceParser()) {
      final var location = TypeSourceLocator.locate(entry, List.of(sourceRoot), parser);

      assertThat(location).isPresent();
      assertThat(location.orElseThrow().getRange().getStart().getLine()).isEqualTo(2);
      assertThat(location.orElseThrow().getRange().getStart().getCharacter()).isEqualTo(12);
    }
  }

  @Test
  void findSourceFileOfCanonicalName_topLevelNestedOrMissingType_findsTheTopLevelFile()
      throws IOException {
    final var outer = write("com/example/Outer.java", "package com.example; class Outer {}");
    final var unnamed = write("Main.java", "class Main {}");
    final List<Path> roots = List.of(sourceRoot);

    assertThat(TypeSourceLocator.findSourceFileOfCanonicalName("com.example.Outer", roots))
        .contains(outer);
    assertThat(TypeSourceLocator.findSourceFileOfCanonicalName("com.example.Outer.Inner", roots))
        .contains(outer);
    assertThat(TypeSourceLocator.findSourceFileOfCanonicalName("Main", roots)).contains(unnamed);
    assertThat(TypeSourceLocator.findSourceFileOfCanonicalName("java.util.Map.Entry", roots))
        .isEmpty();
  }

  @Test
  void locate_missingSourceOrUnsupportedLocalClass_returnsEmpty() throws IOException {
    write(
        "com/example/Outer.java",
        """
        package com.example;
        class Outer {
          void run() {
            class Local {}
          }
        }
        """);

    try (var parser = new SourceParser()) {
      assertThat(
              TypeSourceLocator.locate(entry("com.example.Missing"), List.of(sourceRoot), parser))
          .isEmpty();
      assertThat(
              TypeSourceLocator.locate(
                  entry("com.example.Outer$1Local"), List.of(sourceRoot), parser))
          .isEmpty();
    }
  }

  @Test
  void isNamedDeclaration_namedNestedAndSyntheticTypes_classifiesSupportedTypes() {
    assertThat(TypeSourceLocator.isNamedDeclaration(entry("com.example.Outer$Inner"))).isTrue();
    assertThat(TypeSourceLocator.isNamedDeclaration(entry("com.example.Outer$1Local"))).isFalse();
    assertThat(TypeSourceLocator.isNamedDeclaration(entry("com.example.Outer$1"))).isFalse();
  }

  private Path write(final String relativePath, final String content) throws IOException {
    final var file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    return file;
  }

  private static TypeIndexEntry entry(final String binaryName) {
    final int packageEnd = binaryName.lastIndexOf('.');
    final String packageName = packageEnd >= 0 ? binaryName.substring(0, packageEnd) : "";
    final int simpleNameStart = Math.max(packageEnd, binaryName.lastIndexOf('$')) + 1;
    return new TypeIndexEntry(
        binaryName.substring(simpleNameStart),
        binaryName,
        packageName,
        TypeKind.CLASS,
        true,
        List.of());
  }
}
