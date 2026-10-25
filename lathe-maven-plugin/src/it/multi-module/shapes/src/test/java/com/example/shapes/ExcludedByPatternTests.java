package com.example.shapes;

import org.junit.jupiter.api.Test;

// Matches Surefire's default includes but not this module's, so mvn test skips it, and so must a
// Lathe run of the module.
class ExcludedByPatternTests {

  @Test
  void excludedByPattern() {}
}
