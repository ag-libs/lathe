package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModulePathSplitTest {

  @TempDir Path tmp;

  @Test
  void split_mixedEntries_putsEveryModuleOnTheModulePath() throws Exception {
    final var explicit = jar("explicit.jar", "module-info.class", null);
    final var versioned = jar("versioned.jar", "META-INF/versions/11/module-info.class", null);
    final var automatic = jar("automatic.jar", "com/example/A.class", "com.example.automatic");
    final var plain = jar("plain.jar", "com/example/B.class", null);
    final var modularDir = Files.createDirectories(tmp.resolve("lib/classes"));
    Files.writeString(modularDir.resolve("module-info.class"), "");
    final var plainDir = Files.createDirectories(tmp.resolve("other/classes"));
    final var missing = tmp.resolve("missing.jar");

    final var split =
        ModulePathSplit.split(
            List.of(
                explicit.toString(),
                versioned.toString(),
                automatic.toString(),
                plain.toString(),
                modularDir.toString(),
                plainDir.toString(),
                missing.toString()));

    assertThat(split.get(true))
        .containsExactly(
            explicit.toString(), versioned.toString(), automatic.toString(), modularDir.toString());
    assertThat(split.get(false))
        .containsExactly(plain.toString(), plainDir.toString(), missing.toString());
  }

  private Path jar(final String name, final String entry, final String automaticModuleName)
      throws IOException {
    final var path = tmp.resolve(name);
    final var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (automaticModuleName != null) {
      manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticModuleName);
    }

    try (final var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
      out.putNextEntry(new JarEntry(entry));
      out.closeEntry();
    }

    return path;
  }
}
