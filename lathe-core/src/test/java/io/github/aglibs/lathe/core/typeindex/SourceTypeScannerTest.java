package io.github.aglibs.lathe.core.typeindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SourceTypeScannerTest {

  private static final Path ROOT = Path.of("/jdk/src/java.base/share/classes");

  @Test
  void deriveEntry_nestedPackage_derivesBinaryNameWithUnknownKind() {
    final var entry = SourceTypeScanner.deriveEntry(ROOT, ROOT.resolve("java/util/Foo.java"));

    assertThat(entry).isPresent();
    assertThat(entry.get().simpleName()).isEqualTo("Foo");
    assertThat(entry.get().packageName()).isEqualTo("java.util");
    assertThat(entry.get().binaryName()).isEqualTo("java.util.Foo");
    assertThat(entry.get().kind()).isEqualTo(TypeKind.UNKNOWN);
    assertThat(entry.get().typeNameCandidate()).isTrue();
    assertThat(entry.get().directSupertypes()).isEmpty();
  }

  @Test
  void deriveEntry_defaultPackage_hasEmptyPackageAndBareBinaryName() {
    final var entry = SourceTypeScanner.deriveEntry(ROOT, ROOT.resolve("Foo.java"));

    assertThat(entry).isPresent();
    assertThat(entry.get().packageName()).isEmpty();
    assertThat(entry.get().binaryName()).isEqualTo("Foo");
  }

  @Test
  void deriveEntry_moduleAndPackageInfo_skipped() {
    assertThat(SourceTypeScanner.deriveEntry(ROOT, ROOT.resolve("module-info.java"))).isEmpty();
    assertThat(SourceTypeScanner.deriveEntry(ROOT, ROOT.resolve("java/util/package-info.java")))
        .isEmpty();
  }

  @Test
  void deriveEntry_nonJavaOrOutsideRoot_skipped() {
    assertThat(SourceTypeScanner.deriveEntry(ROOT, ROOT.resolve("java/util/Foo.txt"))).isEmpty();
    assertThat(SourceTypeScanner.deriveEntry(ROOT, Path.of("/other/tree/Bar.java"))).isEmpty();
  }
}
