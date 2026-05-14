package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.ParamStore.PrefixedStore;
import io.github.aglibs.lathe.core.ParamStore.PrefixedWritable;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParamStoreTest {

  @TempDir Path tmp;

  @Test
  void putIndexed_writesNamedEntriesForEachValue() throws Exception {
    final ParamStore store = new ParamStore();
    store.putIndexed(
        "dependencySource",
        List.of(
            new DependencySource("a.jar", "com.example:a:1", "present"),
            new DependencySource("b.jar", "com.example:b:1", "missing")));

    final Path file = tmp.resolve("workspace.properties");
    store.store(file);

    final ParamStore loaded = ParamStore.load(file);
    assertThat(loaded.get("dependencySource.0.jar")).isEqualTo("a.jar");
    assertThat(loaded.get("dependencySource.0.gav")).isEqualTo("com.example:a:1");
    assertThat(loaded.get("dependencySource.0.status")).isEqualTo("present");
    assertThat(loaded.get("dependencySource.1.jar")).isEqualTo("b.jar");
    assertThat(loaded.get("dependencySource.1.gav")).isEqualTo("com.example:b:1");
    assertThat(loaded.get("dependencySource.1.status")).isEqualTo("missing");
  }

  @Test
  void putList_readList_storeLoadRoundtrip() throws Exception {
    final ParamStore store = new ParamStore();
    store.putList("sourceRoots", List.of("/src/main/java", "/src/generated"));

    final Path file = tmp.resolve("params.properties");
    store.store(file);

    final ParamStore loaded = ParamStore.load(file);
    assertThat(loaded.get("sourceRoots.0")).isEqualTo("/src/main/java");
    assertThat(loaded.readList("sourceRoots")).containsExactly("/src/main/java", "/src/generated");
  }

  private record DependencySource(String jar, String gav, String status)
      implements PrefixedWritable {

    @Override
    public void writeTo(final PrefixedStore store) {
      store.set("jar", jar);
      store.set("gav", gav);
      store.set("status", status);
    }
  }
}
