package com.example.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// Every method shape whose result has to land on its discovered test, all passing.
class IdShapesTest {

  @Test
  void plain() {
    System.out.println("shapes: plain on stdout");
  }

  @Test
  void printsToStderr() {
    System.err.println("shapes: printsToStderr on stderr");
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "b"})
  void overloaded(final String value) {
    assertEquals(1, value.length());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void overloaded(final int value) {
    assertTrue(value > 0);
  }

  static Stream<int[]> arrays() {
    return Stream.of(new int[] {1});
  }

  @ParameterizedTest
  @MethodSource("arrays")
  void arrayParam(final int[] values) {
    assertEquals(1, values.length);
  }

  static Stream<Arguments> varargs() {
    return Stream.of(Arguments.of((Object) new String[] {"v"}));
  }

  @ParameterizedTest
  @MethodSource("varargs")
  void varargsParam(final String... values) {
    assertEquals(1, values.length);
  }

  enum Shape {
    ROUND
  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  void enumParam(final Shape shape) {
    assertEquals(Shape.ROUND, shape);
  }

  static Stream<List<String>> lists() {
    return Stream.of(List.of("x"));
  }

  @ParameterizedTest
  @MethodSource("lists")
  void genericParam(final List<String> values) {
    assertEquals(1, values.size());
  }

  @RepeatedTest(2)
  void repeated() {}

  @TestFactory
  Stream<DynamicTest> factory() {
    return Stream.of(DynamicTest.dynamicTest("dynamic", () -> {}));
  }

  @Nested
  class Inner {

    @Test
    void innerTest() {}

    @Nested
    class Deeper {

      @Test
      void deeperTest() {}
    }
  }
}
