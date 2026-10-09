package io.github.aglibs.lathe.format;

// The Phase 0 baseline: the forked formatter must reproduce every GJF golden output byte-for-byte,
// and its output must be stable under reformatting.
final class GoldenConformanceTest {

  private final FormatSubject fork = FormatHarness.latheFormat();

  @GoldenFixtures
  void format_goldenFixture_matchesExpected(
      final String name, final String input, final String expected) throws Exception {
    FormatHarness.assertFormats(fork, input, expected);
  }

  @GoldenFixtures
  void format_goldenFixture_isIdempotent(
      final String name, final String input, final String expected) throws Exception {
    FormatHarness.assertIdempotent(fork, input);
  }
}
