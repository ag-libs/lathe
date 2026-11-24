package io.github.aglibs.lathe.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.Serial;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Json {

  private static final TypeAdapter<Path> PATH_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(final JsonWriter out, final Path value) throws IOException {
          if (value == null) {
            out.nullValue();
          } else {
            out.value(value.toString());
          }
        }

        @Override
        public Path read(final JsonReader in) throws IOException {
          if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
          }
          return Path.of(in.nextString());
        }
      };

  private static final Gson GSON =
      new GsonBuilder()
          .setPrettyPrinting()
          .registerTypeHierarchyAdapter(Path.class, PATH_ADAPTER)
          .create();

  private Json() {}

  public static void write(final Object obj, final Path path) throws IOException {
    Files.createDirectories(path.getParent());
    try (final Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      GSON.toJson(obj, writer);
    }
  }

  // Never null: a file a crash left empty or cut off fails as an IOException, which every caller
  // already handles, not as null (Gson's answer for an empty file) or an unchecked exception.
  public static <T> T read(final Path path, final Class<T> type) throws IOException {
    try (final Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      final T value = GSON.fromJson(reader, type);
      if (value == null) {
        throw new DamagedJsonException("empty JSON file %s".formatted(path), null);
      }

      return value;
    } catch (final JsonParseException e) {
      // Gson wraps the reader's own failures too; only a syntax error means the file is damaged.
      final boolean readFailure =
          e instanceof JsonIOException
              || (e.getCause() instanceof IOException
                  && !(e.getCause() instanceof MalformedJsonException)
                  && !(e.getCause() instanceof EOFException));
      if (readFailure) {
        throw new IOException("unreadable JSON file %s".formatted(path), e);
      }

      throw new DamagedJsonException("malformed JSON file %s".formatted(path), e);
    } catch (final RuntimeException e) {
      // Gson reports a record's compact constructor rejecting what the file holds (a field left
      // out, another schema's shape) as a plain RuntimeException.
      throw new DamagedJsonException("invalid JSON file %s".formatted(path), e);
    }
  }

  // Whether read failed on what the file holds (empty, cut off, or another schema's shape), which
  // writing the file again mends, rather than on reading it (permissions, an I/O error).
  public static boolean isDamaged(final IOException failure) {
    return failure instanceof DamagedJsonException;
  }

  private static final class DamagedJsonException extends IOException {
    @Serial private static final long serialVersionUID = 1L;

    private DamagedJsonException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  public static String toJson(final Object obj) {
    return GSON.toJson(obj);
  }

  public static <T> T fromJson(final String json, final Class<T> type) {
    return GSON.fromJson(json, type);
  }
}
