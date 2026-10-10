package io.github.aglibs.lathe.core.typeindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassSourceFileTest {

  @Test
  void of_topLevelAndPackagePrivateSibling_returnDeclaringSourceName() throws Exception {
    assertThat(ClassSourceFile.of(classFile(ClassSourceFileTest.class)))
        .contains("ClassSourceFileTest.java");
    // A sibling's class file name (SiblingFixture.class) says nothing about its source; the
    // attribute does.
    assertThat(ClassSourceFile.of(classFile(SiblingFixture.class)))
        .contains("ClassSourceFileTest.java");
  }

  @Test
  void of_missingAttributeGarbageOrAbsentFile_returnsEmpty(@TempDir final Path dir)
      throws IOException {
    final var withoutAttribute = dir.resolve("Bare.class");
    Files.write(withoutAttribute, classWithoutAttributes());
    final var garbage = dir.resolve("Garbage.class");
    Files.write(garbage, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

    assertThat(ClassSourceFile.of(withoutAttribute)).isEmpty();
    assertThat(ClassSourceFile.of(garbage)).isEmpty();
    assertThat(ClassSourceFile.of(dir.resolve("Absent.class"))).isEmpty();
  }

  private static Path classFile(final Class<?> type) throws URISyntaxException {
    final var resource = "%s.class".formatted(type.getSimpleName());
    return Path.of(type.getResource(resource).toURI());
  }

  // The shape javac emits under -g:none: a valid class with no SourceFile attribute.
  private static byte[] classWithoutAttributes() throws IOException {
    final var bytes = new ByteArrayOutputStream();
    final var data = new DataOutputStream(bytes);
    data.writeInt(ClassMetadataReader.CLASS_MAGIC);
    data.writeShort(0);
    data.writeShort(65);
    data.writeShort(3);
    data.writeByte(1);
    data.writeUTF("Bare");
    data.writeByte(7);
    data.writeShort(1);
    data.writeShort(0x0020);
    data.writeShort(2);
    data.writeShort(0);
    data.writeShort(0);
    data.writeShort(0);
    data.writeShort(0);
    data.writeShort(0);
    return bytes.toByteArray();
  }
}

final class SiblingFixture {}
