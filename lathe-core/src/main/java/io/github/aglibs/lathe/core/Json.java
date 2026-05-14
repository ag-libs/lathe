package io.github.aglibs.lathe.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.Reader;
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

  public static <T> T read(final Path path, final Class<T> type) throws IOException {
    try (final Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return GSON.fromJson(reader, type);
    }
  }

  public static String toJson(final Object obj) {
    return GSON.toJson(obj);
  }

  public static <T> T fromJson(final String json, final Class<T> type) {
    return GSON.fromJson(json, type);
  }
}
