package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class ParamStore {

  private final Properties props = new Properties();

  public ParamStore() {}

  public void set(final String key, final String value) {
    props.setProperty(key, value);
  }

  public interface PrefixedWritable {

    void writeTo(PrefixedStore store);
  }

  public final class PrefixedStore {

    private final String prefix;

    private PrefixedStore(final String prefix) {
      this.prefix = prefix;
    }

    public void set(final String name, final String value) {
      ParamStore.this.set(prefix + name, value);
    }

    public void setIfPresent(final String name, final Object value) {
      ParamStore.this.setIfPresent(prefix + name, value);
    }
  }

  public String get(final String key) {
    return props.getProperty(key);
  }

  public void putList(final String key, final List<?> values) {
    IntStream.range(0, values.size())
        .forEach(i -> props.setProperty(key + "." + i, values.get(i).toString()));
  }

  public void setIfPresent(final String key, final Object value) {
    if (value != null) {
      set(key, value.toString());
    }
  }

  public void putListIfPresent(final String key, final List<?> values) {
    if (values != null) {
      putList(key, values);
    }
  }

  public void putIndexed(final String key, final List<? extends PrefixedWritable> values) {
    IntStream.range(0, values.size())
        .forEach(
            i -> {
              final PrefixedStore store = new PrefixedStore(key + "." + i + ".");
              values.get(i).writeTo(store);
            });
  }

  public List<String> readList(final String key) {
    return IntStream.iterate(0, i -> props.containsKey(key + "." + i), i -> i + 1)
        .mapToObj(i -> props.getProperty(key + "." + i))
        .toList();
  }

  public void store(final Path file) throws IOException {
    final var sw = new StringWriter();
    props.store(sw, null);
    final var content =
        sw.toString()
            .lines()
            .filter(l -> !l.startsWith("#"))
            .sorted()
            .collect(Collectors.joining(System.lineSeparator()));
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  public static ParamStore load(final Path file) throws IOException {
    final var store = new ParamStore();
    try (final var in = Files.newInputStream(file)) {
      store.props.load(in);
    }
    return store;
  }
}
