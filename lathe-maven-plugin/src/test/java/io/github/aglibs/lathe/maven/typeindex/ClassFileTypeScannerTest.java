package io.github.aglibs.lathe.maven.typeindex;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
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
  void scanJar_standardClassEntries_returnsGraphTypes() throws Exception {
    final Path jar = fixtureJar();

    final List<TypeIndexEntry> entries = ClassFileTypeScanner.scanJar(jar);

    assertFixtureEntries(entries);
  }

  @Test
  void scanDirectory_standardClassFiles_returnsGraphTypes() throws Exception {
    final Path classes = compileFixtureClasses();
    Files.write(classes.resolve("com/example/Broken.class"), new byte[] {0, 1, 2});
    Files.write(classes.resolve("module-info.class"), new byte[] {0, 1, 2});
    Files.write(classes.resolve("com/example/package-info.class"), new byte[] {0, 1, 2});

    final List<TypeIndexEntry> entries = ClassFileTypeScanner.scanDirectory(classes);

    assertFixtureEntries(entries);
  }

  @Test
  void scanDirectory_missingDirectory_returnsEmpty() throws Exception {
    final List<TypeIndexEntry> entries = ClassFileTypeScanner.scanDirectory(tmp.resolve("missing"));

    assertThat(entries).isEmpty();
  }

  private void assertFixtureEntries(final List<TypeIndexEntry> entries) {
    final Map<String, TypeIndexEntry> byBinaryName =
        entries.stream().collect(Collectors.toMap(TypeIndexEntry::binaryName, Function.identity()));
    assertThat(byBinaryName)
        .containsKeys(
            "com.example.PublicType",
            "com.example.PublicType$Nested",
            "com.example.PackagePrivate",
            "com.example.PublicInterface",
            "com.example.PublicEnum",
            "com.example.PublicAnnotation")
        .doesNotContainKeys("com.example.Broken", "com.example.VersionedOnly", "com.example.Meta");
    assertThat(byBinaryName.get("com.example.PublicType").kind()).isEqualTo(TypeKind.CLASS);
    assertThat(byBinaryName.get("com.example.PublicInterface").kind())
        .isEqualTo(TypeKind.INTERFACE);
    assertThat(byBinaryName.get("com.example.PublicEnum").kind()).isEqualTo(TypeKind.ENUM);
    assertThat(byBinaryName.get("com.example.PublicAnnotation").kind())
        .isEqualTo(TypeKind.ANNOTATION);
    assertThat(byBinaryName.get("com.example.PublicType").typeNameCandidate()).isTrue();
    assertThat(byBinaryName.get("com.example.PackagePrivate").typeNameCandidate()).isFalse();
    assertThat(byBinaryName.get("com.example.PublicType$Nested").typeNameCandidate()).isFalse();
    assertThat(byBinaryName.get("com.example.PublicType").directSupertypes())
        .containsExactly("com.example.PackagePrivate");
    assertThat(byBinaryName.get("com.example.PackagePrivate").directSupertypes())
        .containsExactly("java.lang.Object", "com.example.PublicInterface");
    assertThat(byBinaryName.get("com.example.PublicInterface").directSupertypes()).isEmpty();
    assertThat(byBinaryName.get("com.example.PublicAnnotation").directSupertypes())
        .containsExactly("java.lang.annotation.Annotation");
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
            "package com.example; public class PublicType extends PackagePrivate { public static class Nested {} }",
            "PackagePrivate.java",
            "package com.example; class PackagePrivate implements PublicInterface {}",
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
