package io.github.aglibs.lathe.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// A plain test beside a two-invocation parameterized one, run by the runner's own tests.
final class ParameterizedFixture {

  @Test
  void plain_test() {}

  @ParameterizedTest
  @ValueSource(strings = {"a", "b"})
  void param_test(final String value) {}
}
