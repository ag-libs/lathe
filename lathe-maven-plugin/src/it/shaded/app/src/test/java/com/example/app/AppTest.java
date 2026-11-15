package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Runs json's code, whose classes name the relocated Gson: only json's shaded jar holds both.
class AppTest {

  @Test
  void encode_value_usesTheRelocatedGson() {
    assertEquals("[1]\"done\"", App.encode(new int[] {1}));
  }
}
