package com.example.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Fails on purpose: a failure has to report the line of its assertion.
class AssertionsTest {

  @Test
  void singleLine() {
    assertEquals("Hello, Alice!", "Hello, alice!");
  }
}
