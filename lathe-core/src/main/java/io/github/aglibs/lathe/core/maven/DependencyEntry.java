package io.github.aglibs.lathe.core.maven;

import io.github.aglibs.lathe.core.ParamStore;

public record DependencyEntry(String gav, String jar, String status, String dir)
    implements ParamStore.PrefixedWritable {

  public static DependencyEntry readFrom(final ParamStore.PrefixedReader r) {
    final var gav = r.get("gav");
    if (gav == null) {
      return null;
    }
    return new DependencyEntry(gav, r.get("jar"), r.get("status"), r.get("dir"));
  }

  @Override
  public void writeTo(final ParamStore.PrefixedStore store) {
    store.set("gav", gav);
    store.set("status", status);
    store.setIfPresent("jar", jar);
    store.setIfPresent("dir", dir);
  }
}
