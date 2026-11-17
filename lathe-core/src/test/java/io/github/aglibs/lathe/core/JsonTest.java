package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JsonTest {

  @TempDir private Path tmp;

  @Test
  void read_writtenValue_roundTrips() throws IOException {
    final var file = tmp.resolve("value.json");
    Json.write(new ContentFingerprint(42, 7), file);

    assertThat(Json.read(file, ContentFingerprint.class)).isEqualTo(new ContentFingerprint(42, 7));
  }

  // What a crash leaves of a file: nothing, the start of it, or zero bytes the data never filled.
  @Test
  void read_emptyCutOffOrZeroFilledFile_throwsIOExceptionNamingTheFile() throws IOException {
    final List<String> damaged = List.of("", "{\"size\": 4", "\0\0\0\0");
    for (final String content : damaged) {
      final var file = tmp.resolve("damaged.json");
      Files.writeString(file, content);

      assertThatThrownBy(() -> Json.read(file, ContentFingerprint.class))
          .isInstanceOf(IOException.class)
          .hasMessageContaining(file.toString());
    }
  }

  // A failure of the reader itself, here bytes that are not UTF-8, is not reported as damaged JSON.
  @Test
  void read_undecodableBytes_throwsIOExceptionNamingAReadFailure() throws IOException {
    final Path file = Files.write(tmp.resolve("latin1.json"), new byte[] {'{', (byte) 0xFF, '}'});

    assertThatThrownBy(() -> Json.read(file, ContentFingerprint.class))
        .isInstanceOf(IOException.class)
        .hasMessage("unreadable JSON file %s".formatted(file));
  }

  // Well-formed but incomplete, or written by another schema: the record's validation fails.
  @Test
  void read_valueFailingRecordValidation_throwsIOExceptionNamingTheFile() throws IOException {
    final Path file = Files.writeString(tmp.resolve("index.json"), "{\"types\": []}");

    assertThatThrownBy(() -> Json.read(file, TypeIndexFile.class))
        .isInstanceOf(IOException.class)
        .hasMessageContaining(file.toString());
  }
}
