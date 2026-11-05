package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.analysis.testing.SampleFixture;
import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LibrarySourcesTest extends SampleFixture {

  @Test
  void originLabel_jdkType_namesTheModuleAndTheJdkVersionWhenKnown() {
    final var listType = compiled.task().getElements().getTypeElement("java.util.List");

    assertThat(LibrarySources.none().originLabel(listType, compiled.fm())).hasValue("java.base");
    assertThat(LibrarySources.from(List.of(), "21.0.1", null).originLabel(listType, compiled.fm()))
        .hasValue("java.base (JDK 21.0.1)");
    assertThat(LibrarySources.none().originLabel(null, null)).isEmpty();
  }

  @Test
  void originLabel_reactorClass_returnsModuleName() throws Exception {
    final Path classDir = tmp.resolve(LatheLayout.LATHE_DIR).resolve("mymodule");
    Files.createDirectories(classDir);
    final Path sampleSrc = Path.of(compiled.cu().getSourceFile().toUri());
    TestJavac.compileToDir(classDir, sampleSrc);

    try (final var parsed = TestJavac.parseWithClasspath(sampleSrc, classDir)) {
      final var sampleType = parsed.task().getElements().getTypeElement("Sample");

      assertThat(LibrarySources.none().originLabel(sampleType, parsed.fm())).hasValue("mymodule");
    }
  }

  @Test
  void from_dependencySourceClasspath_returnsSelfJarAndTransitiveJars() throws Exception {
    final Path selfJar = tmp.resolve("repo/self.jar");
    final Path transitiveJar = tmp.resolve("repo/transitive.jar");
    final Path sourceDir = tmp.resolve("sources/self");
    final Path sourceFile = sourceDir.resolve("com/example/Self.java");
    final var libraries =
        LibrarySources.from(
            List.of(dependency("com.example:self:1", selfJar, sourceDir, List.of(transitiveJar))),
            null,
            null);

    assertThat(libraries.externalSourceRootForFile(sourceFile)).hasValue(sourceDir);
    assertThat(libraries.containsFile(sourceFile)).isTrue();
    assertThat(libraries.externalSourceDirs()).containsExactly(sourceDir);
    assertThat(libraries.dependencyClasspathForFile(sourceFile))
        .containsExactly(selfJar, transitiveJar);
    assertThat(libraries.containsFile(tmp.resolve("elsewhere/Other.java"))).isFalse();
  }

  // Per-platform classifier jars of one artifact share its sources dir.
  @Test
  void from_classifierJarsSharingASourceDir_takesTheFirstJar() {
    final Path sourceDir = tmp.resolve("sources/natives");
    final Path sourceFile = sourceDir.resolve("com/example/Natives.java");
    final Path linuxJar = tmp.resolve("repo/natives-linux-x86_64.jar");
    final Path osxJar = tmp.resolve("repo/natives-osx-aarch_64.jar");
    final Path otherJar = tmp.resolve("repo/other.jar");
    final var libraries =
        LibrarySources.from(
            List.of(
                dependency("com.example:natives:1", linuxJar, sourceDir, List.of(otherJar)),
                dependency("com.example:natives:1", osxJar, sourceDir, List.of())),
            null,
            null);

    assertThat(libraries.externalSourceDirs()).containsExactly(sourceDir);
    assertThat(libraries.dependencyClasspathForFile(sourceFile))
        .startsWith(linuxJar, otherJar)
        .contains(osxJar);
  }

  // A test-jar dependency and a tests-classifier one name the same jar.
  @Test
  void from_jarListedTwice_takesTheFirstEntry() {
    final Path jar = tmp.resolve("repo/lib-1-tests.jar");
    final Path sourceDir = tmp.resolve("sources/lib");
    final var libraries =
        LibrarySources.from(
            List.of(
                dependency("com.example:lib:test-jar:tests:1", jar, sourceDir, List.of()),
                dependency("com.example:lib:jar:tests:1", jar, sourceDir, List.of())),
            null,
            null);

    assertThat(libraries.jarToGav())
        .containsExactly(entry(jar, "com.example:lib:test-jar:tests:1"));
    assertThat(libraries.dependencySourceDir(jar)).hasValue(sourceDir);
  }

  // The jar URI is percent-encoded; a space in the path (C:\Users\First Last\.m2) must survive.
  @Test
  void externalSourceRoot_classpathJarUnderAPathWithASpace_returnsMappedSourceDir()
      throws Exception {
    final Path sourceDir = tmp.resolve("sources/greeter");
    final Path greeterSrc = sourceDir.resolve("com/example/Greeter.java");
    Files.createDirectories(greeterSrc.getParent());
    Files.writeString(greeterSrc, "package com.example; public class Greeter {}");

    final Path jar = tmp.resolve("First Last/repo/greeter.jar");
    Files.createDirectories(jar.getParent());
    TestJavac.compileToJar(jar, tmp.resolve("classes/greeter"), List.of(), greeterSrc);
    final var libraries =
        LibrarySources.from(
            List.of(dependency("com.example:greeter:1", jar, sourceDir, List.of())), null, null);

    final Path userSrc = tmp.resolve("User.java");
    Files.writeString(userSrc, "import com.example.Greeter; public class User { Greeter g; }");
    try (final var parsed = TestJavac.parseWithClasspath(userSrc, jar)) {
      final var greeterElement = parsed.task().getElements().getTypeElement("com.example.Greeter");

      assertThat(libraries.externalSourceRoot(greeterElement, parsed.fm())).hasValue(sourceDir);
    }
  }

  private static DependencyData dependency(
      final String gav, final Path jar, final Path sourceDir, final List<Path> classpath) {
    return new DependencyData(
        gav,
        jar.toString(),
        SourceStatus.PRESENT,
        sourceDir.toString(),
        classpath.stream().map(Path::toString).toList(),
        null);
  }
}
