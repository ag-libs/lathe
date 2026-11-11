package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
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
    assertThat(versionFromJar(tmp.resolve("versioned.jar"), manifest("1.2.3"))).contains("1.2.3");
    assertThat(versionFromJar(tmp.resolve("blank.jar"), manifest(" "))).isEmpty();
    assertThat(versionFromJar(tmp.resolve("unversioned.jar"), new Manifest())).isEmpty();
    assertThat(LatheBuildInfo.version(LatheBuildInfoTest.class)).isEmpty();
  }

  private static Manifest manifest(final String version) {
    final var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
    return manifest;
  }

  // Marker packed alone in a jar with this manifest and loaded from it, with no parent loader to
  // find it on the test classpath first.
  private static Optional<String> versionFromJar(final Path jar, final Manifest manifest)
      throws IOException, ClassNotFoundException {
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
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
