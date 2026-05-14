package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.maven.jdk.JdkSource;
import io.github.aglibs.lathe.maven.jdk.JdkSourceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkSourceResolverTest {

  @TempDir Path tmp;

  @Test
  void resolve_missingJavaHome_returnsMissing() {
    final JdkSource result = JdkSourceResolver.resolve(Map.of());
    assertThat(result.isPresent()).isFalse();
    assertThat(result.home()).isNull();
  }

  @Test
  void resolve_noSrcZip_returnsMissingWithHome() {
    final JdkSource result = JdkSourceResolver.resolve(Map.of("JAVA_HOME", tmp.toString()));
    assertThat(result.isPresent()).isFalse();
    assertThat(result.home()).isEqualTo(tmp);
  }

  @Test
  void resolve_srcZipPresent_returnsPresentWithCorrectPaths() throws IOException {
    Files.createDirectories(tmp.resolve("lib"));
    Files.createFile(tmp.resolve("lib/src.zip"));

    final JdkSource result = JdkSourceResolver.resolve(Map.of("JAVA_HOME", tmp.toString()));

    assertThat(result.isPresent()).isTrue();
    assertThat(result.home()).isEqualTo(tmp);
    assertThat(result.sourceZip()).isEqualTo(tmp.resolve("lib/src.zip"));
    // sourceDir is <cacheRoot>/jdks/<sanitizedVendor>/<sanitizedVersion>
    assertThat(result.sourceDir().getParent().getParent().getFileName().toString())
        .isEqualTo("jdks");
    assertThat(result.sourceDir().toString()).doesNotContain(" ");
  }
}
