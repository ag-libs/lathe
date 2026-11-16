package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.schema.ContentFingerprint;
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
}
