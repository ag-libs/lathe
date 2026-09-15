package com.example.jpms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreetTest {

  @Test
  void greet_returnsGreeting() {
    assertEquals("Hello, World!!", Hello.greet("World"));
  }
}
