package com.example.jpms;

// A `main` method that lives in test sources of a modular (JPMS) module. It is compiled into the
// module's test output and patched into the module at test time, so it can only be run/debugged via
// the module's captured test launch (patched module + test graph), not the main launch -- exercises
// the test-scope main routing (LaunchPlan.forTestMain).
public final class HelloTester {

  private HelloTester() {}

  public static void main(final String[] args) {
    final int n = args.length + 42;
    System.out.println("tester n=" + n);
  }
}
