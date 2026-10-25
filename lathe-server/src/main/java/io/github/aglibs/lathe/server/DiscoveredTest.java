package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

// One test a module run would execute: its position id (package, class and nesting read off it, as
// for run results), its method name, and its source file's URI (empty when it resolves to none).
record DiscoveredTest(String id, String label, String uri) {

  DiscoveredTest {
    ValidCheck.check().notBlank(id, "id").notBlank(label, "label").notNull(uri, "uri").validate();
  }
}
