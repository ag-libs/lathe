package io.github.aglibs.lathe.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheBuildInfoTest {

  @Test
  void summary_anyClass_namesTheRunningJvm() {
    assertThat(LatheBuildInfo.summary(LatheBuildInfoTest.class))
        .startsWith("version=")
        .contains(
            "java=%s".formatted(Runtime.version()),
            "java.home=%s".formatted(System.getProperty("java.home")));
  }

  // Read from the class's own jar, so a class in a named module has it too; a blank or missing
  // version, or a class from a classes directory, has none.
  @Test
  void version_classInJar_itsManifestVersion_blankMissingOrDirectoryEmpty(@TempDir final Path tmp)
      throws Exception {
    assertThat(versionInJar(tmp, "Implementation-Version: 1.2.3")).contains("1.2.3");
    assertThat(versionInJar(tmp, "Implementation-Version:  ")).isEmpty();
    assertThat(versionInJar(tmp, "")).isEmpty();
    assertThat(LatheBuildInfo.version(LatheBuildInfoTest.class)).isEmpty();
  }

  // Marker packed alone in a jar with these manifest lines and loaded from it, with no parent
  // loader to find it on the test classpath first.
  private static Optional<String> versionInJar(final Path dir, final String manifestLines)
      throws IOException, ClassNotFoundException {
    final Path jar = Files.createTempFile(dir, "marker", ".jar");
    final var manifest =
        new Manifest(
            new ByteArrayInputStream(
                "Manifest-Version: 1.0\n%s\n".formatted(manifestLines).getBytes(UTF_8)));
    final String entry = "%s.class".formatted(Marker.class.getName().replace('.', '/'));
    try (final var out = new JarOutputStream(Files.newOutputStream(jar), manifest);
        final var in = LatheBuildInfoTest.class.getResourceAsStream("/%s".formatted(entry))) {
      out.putNextEntry(new JarEntry(entry));
      in.transferTo(out);
    }

    try (final var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      return LatheBuildInfo.version(loader.loadClass(Marker.class.getName()));
    }
  }

  static final class Marker {}
}
