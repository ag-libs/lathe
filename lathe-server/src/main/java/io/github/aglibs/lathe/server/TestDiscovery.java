package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// The tests a module run would execute, found without running them; empty with the reasons when
// the module can't be launched (no captured test launch, an incomplete capture).
record TestDiscovery(String moduleRel, List<String> blockedReasons, List<DiscoveredTest> tests) {

  TestDiscovery {
    ValidCheck.check()
        .notNull(moduleRel, "moduleRel")
        .notNull(blockedReasons, "blockedReasons")
        .notNull(tests, "tests")
        .validate();
    blockedReasons = List.copyOf(blockedReasons);
    tests = List.copyOf(tests);
  }
}
