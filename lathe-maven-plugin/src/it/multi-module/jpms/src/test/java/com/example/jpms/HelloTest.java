package com.example.jpms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HelloTest {

  @Test
  void greet_returnsExpectedMessage() {
    assertEquals("Hello, World!", Hello.greet("World"));
  }
}
