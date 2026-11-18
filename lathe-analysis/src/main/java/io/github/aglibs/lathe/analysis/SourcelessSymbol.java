package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.SymbolKind;

// A type a symbol search finds in a dependency without sources: no file to point at, so it names
// the dependency that declares it instead.
public record SourcelessSymbol(String name, SymbolKind kind, String container, String dependency) {

  public SourcelessSymbol {
    ValidCheck.check()
        .notBlank(name, "name")
        .notNull(kind, "kind")
        .notNull(container, "container")
        .notBlank(dependency, "dependency")
        .validate();
  }
}
