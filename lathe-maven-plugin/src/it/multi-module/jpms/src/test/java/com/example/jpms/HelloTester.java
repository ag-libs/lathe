package com.example.jpms;

import java.util.ArrayList;
import java.util.List;

// A `main` method that lives in test sources of a modular (JPMS) module. It is compiled into the
// module's test output and patched into the module at test time, so it can only be run/debugged via
// the module's captured test launch (patched module + test graph), not the main launch -- exercises
// the test-scope main routing (LaunchPlan.forTestMain). The `items` list gives the debugger a
// collection to expand (object-scoped evaluation / logical structure views).
public final class HelloTester {

  private HelloTester() {}

  public static void main(final String[] args) {
    final List<String> items = new ArrayList<>(List.of("alpha", "beta", "gamma"));
    final int n = items.size() + args.length;
    System.out.println("tester n=" + n);
  }
}
