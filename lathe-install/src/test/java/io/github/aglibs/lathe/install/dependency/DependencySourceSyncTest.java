package io.github.aglibs.lathe.install.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.testing.ZipFixture;
import io.github.aglibs.lathe.install.ResolvedJar;
import io.github.aglibs.lathe.install.typeindex.AbstractCachePropertyTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencySourceSyncTest extends AbstractCachePropertyTest {

  @TempDir Path tmp;

  @Test
  void extract_writesMarkerAfterSuccess() throws IOException {
    final DependencySource source = presentSource();

    DependencySourceSync.extract(List.of(source));

    final var content = Files.readString(source.dir().resolve(".lathe-source.json"));
    assertThat(content)
        .contains("\"schema\"")
        .contains("\"" + LatheLayout.CACHE_SCHEMA_VERSION + "\"");
    assertThat(content).contains("\"gav\"").contains("com.example:dep:1.0");
    assertThat(content).contains("\"sourceJar\"");
    final var marker = Json.fromJson(content, MarkerSnapshot.class);
    assertThat(marker.schema()).isEqualTo(LatheLayout.CACHE_SCHEMA_VERSION);
    assertThat(marker.gav()).isEqualTo("com.example:dep:1.0");
    assertThat(marker.sourceJar()).isEqualTo(source.jar().toString());
  }

  @Test
  void extract_skipsWhenMarkerMatches() throws IOException {
    final DependencySource source = presentSource();

    DependencySourceSync.extract(List.of(source));
    Files.writeString(source.dir().resolve("sentinel"), "unchanged");

    DependencySourceSync.extract(List.of(source));

    assertThat(source.dir().resolve("sentinel")).hasContent("unchanged");
  }

  // A crash can leave the marker truncated to nothing, which Gson reads as null: the sources are
  // extracted again and the marker rewritten, instead of every later sync failing.
  @Test
  void extract_emptyMarker_reextractsAndRewritesMarker() throws IOException {
    final DependencySource source = presentSource();
    DependencySourceSync.extract(List.of(source));
    final var marker = source.dir().resolve(LatheLayout.DEPENDENCY_SOURCE_FILENAME);
    Files.writeString(source.dir().resolve("sentinel"), "stale");
    Files.writeString(marker, "");

    DependencySourceSync.extract(List.of(source));

    assertThat(source.dir().resolve("sentinel")).doesNotExist();
    assertThat(source.dir().resolve("com/example/A.java")).exists();
    assertThat(Json.read(marker, MarkerSnapshot.class).schema())
        .isEqualTo(LatheLayout.CACHE_SCHEMA_VERSION);
  }

  // One source with a -sources jar and one without: the first is extracted, and each gets its jar's
  // type index.
  @Test
  void prepare_presentAndMissingSources_extractsAndIndexesEach() throws IOException {
    System.setProperty(LatheFlags.CACHE_ROOT, tmp.resolve("cache").toString());
    final DependencySource present = presentSource();
    final Path bareJar = ZipFixture.create(tmp.resolve("bare.jar"), "b/B.class", "x");
    final var bare = DependencySource.missing("com.example:bare:1.0", bareJar, List.of());
    final List<ResolvedJar> jars =
        List.of(
            new ResolvedJar("com.example", "dep", "1.0", "", present.jar()),
            new ResolvedJar("com.example", "bare", "1.0", "", bareJar));

    final List<DependencySource> prepared =
        DependencySourceSync.prepare(List.of(present, bare), jars);

    assertThat(present.dir().resolve("com/example/A.java")).exists();
    assertThat(prepared).allSatisfy(source -> assertThat(source.typeIndex()).isNotNull().exists());
  }

  // The DB2 driver publishes a text file as its -sources jar: that dependency gets no sources, and
  // the others are extracted as usual.
  @Test
  void prepare_sourcesJarNotAZip_isMissingAndOthersExtracted() throws IOException {
    System.setProperty(LatheFlags.CACHE_ROOT, tmp.resolve("cache").toString());
    final DependencySource present = presentSource();
    final DependencySource unreadable = notAZipSource();
    final List<ResolvedJar> jars =
        List.of(
            new ResolvedJar("com.example", "dep", "1.0", "", present.jar()),
            new ResolvedJar("com.example", "notzip", "1.0", "", unreadable.jar()));

    final List<DependencySource> prepared =
        DependencySourceSync.prepare(List.of(present, unreadable), jars);

    assertThat(present.dir().resolve("com/example/A.java")).exists();
    assertThat(prepared)
        .extracting(DependencySource::gav, DependencySource::status)
        .containsExactly(
            tuple("com.example:dep:1.0", SourceStatus.PRESENT),
            tuple("com.example:notzip:1.0", SourceStatus.MISSING));
  }

  @Test
  void extract_sourcesJarNotAZip_answersItAndLeavesNoCache() throws IOException {
    final DependencySource unreadable = notAZipSource();

    final Set<String> skipped = DependencySourceSync.extract(List.of(unreadable));

    assertThat(skipped).containsExactly("com.example:notzip:1.0");
    assertThat(unreadable.dir()).doesNotExist();
    try (Stream<Path> leftovers = Files.list(unreadable.dir().getParent())) {
      assertThat(leftovers).isEmpty();
    }
  }

  @Test
  void extract_readableSourcesJar_answersNoneSkipped() throws IOException {
    final Set<String> skipped = DependencySourceSync.extract(List.of(presentSource()));

    assertThat(skipped).isEmpty();
  }

  private DependencySource notAZipSource() throws IOException {
    final Path jar = tmp.resolve("notzip-sources.jar");
    Files.writeString(jar, "\"Copyright. The source code for this program is not published.\"");
    final var artifact = new ResolvedJar("com.example", "notzip", "1.0", "sources", jar);
    return DependencySource.present(
        "com.example:notzip:1.0", jar, tmp.resolve("cache/notzip"), artifact, List.of());
  }

  private DependencySource presentSource() throws IOException {
    final Path jar =
        ZipFixture.create(tmp.resolve("dep-sources.jar"), "com/example/A.java", "class A {}");
    final var artifact = new ResolvedJar("com.example", "dep", "1.0", "sources", jar);
    return DependencySource.present(
        "com.example:dep:1.0", jar, tmp.resolve("cache/dep"), artifact, List.of());
  }

  private record MarkerSnapshot(String schema, String gav, String sourceJar) {}
}
