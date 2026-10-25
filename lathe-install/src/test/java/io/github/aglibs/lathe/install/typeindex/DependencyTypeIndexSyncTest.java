package io.github.aglibs.lathe.install.typeindex;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.install.ResolvedJar;
import io.github.aglibs.lathe.install.ZipFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyTypeIndexSyncTest extends AbstractCachePropertyTest {

  @TempDir Path tmp;

  @Test
  void index_missingIndex_writesShard() throws Exception {
    useTempCache();
    final ResolvedJar artifact = artifact();

    index(artifact);

    final TypeIndexFile file = readIndex(artifact);
    assertThat(file.schema()).isEqualTo(LatheLayout.CACHE_SCHEMA_VERSION);
    assertThat(file.origin().dependency().gav()).isEqualTo("com.example:dep:1.0");
    assertThat(file.origin().dependency().jar()).isEqualTo(artifact.jar().toString());
    assertThat(file.types()).isEmpty();
  }

  @Test
  void index_freshIndex_reusesShard() throws Exception {
    useTempCache();
    final ResolvedJar artifact = artifact();
    final Path index = DependencyTypeIndexSync.indexPath(artifact);

    index(artifact);
    Files.setLastModifiedTime(index, FileTime.fromMillis(1000));

    index(artifact);

    assertThat(Files.getLastModifiedTime(index).toMillis()).isEqualTo(1000);
  }

  @Test
  void index_staleSchema_rebuildsShard() throws Exception {
    useTempCache();
    final ResolvedJar artifact = artifact();
    final Path index = DependencyTypeIndexSync.indexPath(artifact);

    index(artifact);
    final String stale =
        Files.readString(index)
            .replace(
                "\"schema\": \"%s\"".formatted(LatheLayout.CACHE_SCHEMA_VERSION),
                "\"schema\": \"old\"");
    Files.writeString(index, stale);

    index(artifact);

    assertThat(readIndex(artifact).schema()).isEqualTo(LatheLayout.CACHE_SCHEMA_VERSION);
  }

  private void useTempCache() {
    System.setProperty(LatheFlags.CACHE_ROOT, tmp.resolve("cache").toString());
  }

  private void index(final ResolvedJar artifact) {
    DependencyTypeIndexSync.index(List.of(artifact));
  }

  private TypeIndexFile readIndex(final ResolvedJar artifact) throws IOException {
    return Json.read(DependencyTypeIndexSync.indexPath(artifact), TypeIndexFile.class);
  }

  private ResolvedJar artifact() throws IOException {
    final String groupId = "com.example";
    final String artifactId = "dep";
    final String version = "1.0";
    final Path jar = tmp.resolve("%s-%s.jar".formatted(artifactId, version));
    ZipFixture.create(jar, Map.of("ignored.txt", new byte[] {1}));
    return new ResolvedJar(groupId, artifactId, version, "", jar);
  }
}
