package com.example.shapes;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Fails on purpose at class level: the error belongs to the class, and no method runs.
class BrokenSetupTest {

  @BeforeAll
  static void setUp() {
    throw new IllegalStateException("setup exploded");
  }

  @Test
  void neverRuns() {}
}
