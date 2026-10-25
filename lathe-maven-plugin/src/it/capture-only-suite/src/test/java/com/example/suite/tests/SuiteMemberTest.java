package com.example.suite.tests;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SuiteMemberTest {

  // Capture-only mode must not run it.
  @Test
  void runsOnlyOutsideCaptureOnly() {
    assertNotEquals("true", System.getProperty("lathe.capture.only"));
  }
}
