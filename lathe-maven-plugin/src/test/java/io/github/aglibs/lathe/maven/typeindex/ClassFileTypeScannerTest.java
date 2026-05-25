package io.github.aglibs.lathe.maven.typeindex;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.maven.ZipFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileTypeScannerTest {

  @TempDir Path tmp;

  @Test
  void scanJar_standardClassEntries_returnsPublicTopLevelTypes() throws Exception {
    final Path jar = fixtureJar();

    final List<TypeIndexEntry> entries = new ClassFileTypeScanner().scanJar(jar);

    final Map<String, TypeIndexEntry> byQualifiedName =
        entries.stream()
            .collect(Collectors.toMap(TypeIndexEntry::qualifiedName, Function.identity()));
    assertThat(byQualifiedName)
        .containsKeys(
            "com.example.PublicType",
            "com.example.PublicInterface",
            "com.example.PublicEnum",
            "com.example.PublicAnnotation")
        .doesNotContainKeys(
            "com.example.PackagePrivate",
            "com.example.PublicType.Nested",
            "com.example.Broken",
            "com.example.VersionedOnly",
            "com.example.Meta");
    assertThat(byQualifiedName.get("com.example.PublicType").kind()).isEqualTo(TypeKind.CLASS);
    assertThat(byQualifiedName.get("com.example.PublicInterface").kind())
        .isEqualTo(TypeKind.INTERFACE);
    assertThat(byQualifiedName.get("com.example.PublicEnum").kind()).isEqualTo(TypeKind.ENUM);
    assertThat(byQualifiedName.get("com.example.PublicAnnotation").kind())
        .isEqualTo(TypeKind.ANNOTATION);
  }

  private Path fixtureJar() throws IOException {
    final Path classes = compileFixtureClasses();
    final Path jar = tmp.resolve("fixture.jar");
    return ZipFixture.createFromDirectory(
        jar,
        classes,
        Map.of(
            "com/example/Broken.class",
            new byte[] {0, 1, 2},
            "META-INF/com/example/Meta.class",
            new byte[] {0, 1, 2},
            "META-INF/versions/9/com/example/VersionedOnly.class",
            Files.readAllBytes(classes.resolve("com/example/PublicType.class")),
            "module-info.class",
            new byte[] {0, 1, 2},
            "com/example/package-info.class",
            new byte[] {0, 1, 2}));
  }

  private Path compileFixtureClasses() throws IOException {
    final Path src = tmp.resolve("src");
    final Path classes = tmp.resolve("classes");
    Files.createDirectories(src.resolve("com/example"));
    Files.createDirectories(classes);
    final Map<String, String> sources =
        Map.of(
            "PublicType.java",
            "package com.example; public class PublicType { public static class Nested {} }",
            "PackagePrivate.java",
            "package com.example; class PackagePrivate {}",
            "PublicInterface.java",
            "package com.example; public interface PublicInterface {}",
            "PublicEnum.java",
            "package com.example; public enum PublicEnum { A }",
            "PublicAnnotation.java",
            "package com.example; public @interface PublicAnnotation {}");
    writeSources(src, sources);

    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    final List<String> args =
        sources.keySet().stream()
            .map(fileName -> src.resolve("com/example").resolve(fileName).toString())
            .collect(Collectors.toList());
    args.addFirst(classes.toString());
    args.addFirst("-d");
    final int result = compiler.run(null, null, null, args.toArray(String[]::new));
    assertThat(result).isZero();
    return classes;
  }

  private void writeSources(final Path src, final Map<String, String> sources) throws IOException {
    for (final Map.Entry<String, String> source : sources.entrySet()) {
      Files.writeString(src.resolve("com/example").resolve(source.getKey()), source.getValue());
    }
  }
}
