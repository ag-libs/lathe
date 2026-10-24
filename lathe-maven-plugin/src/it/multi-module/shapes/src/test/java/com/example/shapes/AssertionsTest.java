package com.example.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

// Fails on purpose: each failure reports its line, and its compared values when it has them.
class AssertionsTest {

  @Test
  void singleLine() {
    assertEquals("Hello, Alice!", "Hello, alice!");
  }

  @Test
  void multiLine() {
    assertEquals("line1\nline2", "line1\nLINE2");
  }

  @Test
  void noValues() {
    fail("no comparison");
  }
}
