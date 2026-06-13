package io.github.aglibs.lathe.maven.jdk;

import static org.assertj.core.api.Assertions.assertThat;

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
    assertThat(result.cacheKey()).isNotBlank().doesNotContain(" ");
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
    assertThat(result.sourceDir().getParent().getFileName().toString()).isEqualTo("jdks");
    assertThat(result.cacheKey()).isNotBlank();
    assertThat(result.sourceDir().getFileName().toString()).isEqualTo(result.cacheKey());
    assertThat(result.sourceDir().toString()).doesNotContain(" ");
  }

  @Test
  void cacheKey_correttoRelease_returnsCorrettoKey() throws IOException {
    writeRelease(
        """
        IMPLEMENTOR="Amazon.com Inc."
        IMPLEMENTOR_VERSION="Corretto-26.0.0.35.2"
        JAVA_VERSION="26"
        """);
    assertThat(JdkSourceResolver.cacheKey(tmp, "any", "0")).isEqualTo("corretto-26.0.0.35.2");
  }

  @Test
  void cacheKey_temurinRelease_returnsTemurinKey() throws IOException {
    writeRelease(
        """
        IMPLEMENTOR="Eclipse Adoptium"
        IMPLEMENTOR_VERSION="Temurin-21.0.5+11"
        JAVA_VERSION="21.0.5"
        """);
    assertThat(JdkSourceResolver.cacheKey(tmp, "any", "0")).isEqualTo("temurin-21.0.5-11");
  }

  @Test
  void cacheKey_oracleRelease_stripsCorpAndUsesJavaVersion() throws IOException {
    writeRelease(
        """
        IMPLEMENTOR="Oracle Corporation"
        JAVA_VERSION="21.0.5"
        """);
    assertThat(JdkSourceResolver.cacheKey(tmp, "any", "0")).isEqualTo("oracle-21.0.5");
  }

  @Test
  void cacheKey_withCorrettoReleaseFile_sourceDirUsesKey() throws IOException {
    Files.createDirectories(tmp.resolve("lib"));
    Files.createFile(tmp.resolve("lib/src.zip"));
    writeRelease(
        """
        IMPLEMENTOR="Amazon.com Inc."
        IMPLEMENTOR_VERSION="Corretto-26.0.0.35.2"
        JAVA_VERSION="26"
        """);

    final JdkSource result = JdkSourceResolver.resolve(Map.of("JAVA_HOME", tmp.toString()));

    assertThat(result.cacheKey()).isEqualTo("corretto-26.0.0.35.2");
    assertThat(result.sourceDir().getFileName().toString()).isEqualTo("corretto-26.0.0.35.2");
  }

  private void writeRelease(final String content) throws IOException {
    Files.writeString(tmp.resolve("release"), content);
  }
}
