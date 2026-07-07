package com.example.jpms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HelloTest {

  @Test
  void greet_returnsExpectedMessage() {
    assertEquals("Hello, World!", Hello.greet("World"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Alice", "Bob"})
  void greet_name_returnsPersonalGreeting(final String name) {
    assertEquals("Hello, %s!".formatted(name), Hello.greet(name));
  }

  @Test
  void greet_resource_returnsExpectedContent() throws IOException {
    try (final var in = getClass().getResourceAsStream("/com/example/jpms/test-resource.txt")) {
      assertNotNull(in);
      assertEquals("jpms-test-resource\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
