package io.github.aglibs.lathe.maven.typeindex;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.maven.ZipFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyTypeIndexSyncTest {

  private static final Log LOG = new SystemStreamLog();

  @TempDir Path tmp;

  private String previousCache;

  @BeforeEach
  void rememberCacheOverride() {
    previousCache = System.getProperty("lathe.cache");
  }

  @AfterEach
  void restoreCacheOverride() {
    if (previousCache == null) {
      System.clearProperty("lathe.cache");
      return;
    }

    System.setProperty("lathe.cache", previousCache);
  }

  @Test
  void index_missingIndex_writesShard() throws Exception {
    useTempCache();
    final Artifact artifact = artifact();

    index(artifact);

    final TypeIndexFile file = readIndex(artifact);
    assertThat(file.schema()).isEqualTo(LatheLayout.SCHEMA_VERSION);
    assertThat(file.origin().dependency().gav()).isEqualTo("com.example:dep:1.0");
    assertThat(file.origin().dependency().jar()).isEqualTo(artifact.getFile().toPath().toString());
    assertThat(file.types()).isEmpty();
  }

  @Test
  void index_freshIndex_reusesShard() throws Exception {
    useTempCache();
    final Artifact artifact = artifact();
    final Path index = DependencyTypeIndexSync.indexPath(artifact);

    index(artifact);
    Files.setLastModifiedTime(index, FileTime.fromMillis(1000));

    index(artifact);

    assertThat(Files.getLastModifiedTime(index).toMillis()).isEqualTo(1000);
  }

  @Test
  void index_staleSchema_rebuildsShard() throws Exception {
    useTempCache();
    final Artifact artifact = artifact();
    final Path index = DependencyTypeIndexSync.indexPath(artifact);

    index(artifact);
    final String stale =
        Files.readString(index).replace("\"schema\": \"1\"", "\"schema\": \"old\"");
    Files.writeString(index, stale);

    index(artifact);

    assertThat(readIndex(artifact).schema()).isEqualTo(LatheLayout.SCHEMA_VERSION);
  }

  private void useTempCache() {
    System.setProperty("lathe.cache", tmp.resolve("cache").toString());
  }

  private void index(final Artifact artifact) {
    DependencyTypeIndexSync.index(List.of(artifact), LOG);
  }

  private TypeIndexFile readIndex(final Artifact artifact) throws IOException {
    return Json.read(DependencyTypeIndexSync.indexPath(artifact), TypeIndexFile.class);
  }

  private Artifact artifact() throws IOException {
    final String groupId = "com.example";
    final String artifactId = "dep";
    final String version = "1.0";
    final Path jar = tmp.resolve("%s-%s.jar".formatted(artifactId, version));
    ZipFixture.create(jar, Map.of("ignored.txt", new byte[] {1}));
    final Artifact artifact =
        new DefaultArtifact(groupId, artifactId, version, Artifact.SCOPE_COMPILE, "jar", "", null);
    artifact.setFile(jar.toFile());
    return artifact;
  }
}
