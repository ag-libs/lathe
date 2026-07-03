package io.github.aglibs.lathe.maven.typeindex;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOriginKind;
import io.github.aglibs.lathe.maven.jdk.JdkSource;
import io.github.aglibs.lathe.maven.jdk.JdkSourceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkTypeIndexSyncTest extends AbstractCachePropertyTest {

  private static final Log LOG = new SystemStreamLog();

  @TempDir Path tmp;

  @Test
  void index_missingIndex_writesShard() throws Exception {
    useTempCache();
    final JdkSource source = source();

    final JdkSource indexed = JdkTypeIndexSync.index(source, LOG);

    final TypeIndexFile file = readIndex(source);
    assertThat(indexed.typeIndex()).isEqualTo(JdkTypeIndexSync.indexPath(source));
    assertThat(file.schema()).isEqualTo(LatheLayout.SCHEMA_VERSION);
    assertThat(file.origin().kind()).isEqualTo(TypeIndexOriginKind.JDK);
    assertThat(file.origin().jdk().javaHome()).isEqualTo(source.home().toString());
    assertThat(file.origin().jdk().vendor()).isEqualTo(source.vendor());
    assertThat(file.origin().jdk().version()).isEqualTo(source.version());
    assertThat(file.types())
        .extracting(TypeIndexEntry::qualifiedName)
        .contains("java.lang.String", "java.util.List");
  }

  @Test
  void index_freshIndex_reusesShard() throws Exception {
    useTempCache();
    final JdkSource source = source();
    final Path index = JdkTypeIndexSync.indexPath(source);

    JdkTypeIndexSync.index(source, LOG);
    Files.setLastModifiedTime(index, FileTime.fromMillis(1000));

    JdkTypeIndexSync.index(source, LOG);

    assertThat(Files.getLastModifiedTime(index).toMillis()).isEqualTo(1000);
  }

  @Test
  void index_staleSchema_rebuildsShard() throws Exception {
    useTempCache();
    final JdkSource source = source();
    final Path index = JdkTypeIndexSync.indexPath(source);

    JdkTypeIndexSync.index(source, LOG);
    final String stale =
        Files.readString(index)
            .replace(
                "\"schema\": \"%s\"".formatted(LatheLayout.SCHEMA_VERSION), "\"schema\": \"old\"");
    Files.writeString(index, stale);

    JdkTypeIndexSync.index(source, LOG);

    assertThat(readIndex(source).schema()).isEqualTo(LatheLayout.SCHEMA_VERSION);
  }

  @Test
  void index_nullHome_returnsUnchangedSource() {
    useTempCache();
    final JdkSource source = JdkSource.missing("vendor", "version", "cache-key", null);

    assertThat(JdkTypeIndexSync.index(source, LOG)).isSameAs(source);
  }

  private void useTempCache() {
    System.setProperty("lathe.cache", tmp.resolve("cache").toString());
  }

  private TypeIndexFile readIndex(final JdkSource source) throws IOException {
    return Json.read(JdkTypeIndexSync.indexPath(source), TypeIndexFile.class);
  }

  private JdkSource source() {
    final var resolved =
        JdkSourceResolver.resolve(Map.of("JAVA_HOME", System.getProperty("java.home")));
    return JdkSource.missing(
        resolved.vendor(), resolved.version(), resolved.cacheKey(), resolved.home());
  }
}
