package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.SourceParser;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

// The member-stub parse (CQ-0065), which full completion reaches only where the first parse lost
// the sentinel: it answers for a record's member start and nowhere else.
class SentinelParserTest {

  @Test
  void parseMemberStub_recordMemberStart_findsTheSentinel() {
    assertThat(
            parseMemberStub(
                "record Test(int x) {\n    St§\n}", SentinelInjectionResult::asMemberStub))
        .isPresent();
  }

  @Test
  void parseMemberStub_classMemberStartOrRecordMethodBody_answersNothing() {
    assertThat(parseMemberStub("class Test {\n    St§\n}", SentinelInjectionResult::asMemberStub))
        .isEmpty();
    assertThat(
            parseMemberStub(
                "record Test(int x) {\n    void m() {\n        St§\n    }\n}",
                SentinelInjectionResult::asMemberStub))
        .isEmpty();
  }

  // The name stub (CQ-0076) holds the sentinel as the stub method's name; the slot is the member's
  // name, with the member's type as its declared type.
  @Test
  void parseMemberStub_recordMemberName_findsTheNameSlot() {
    final ParsedSentinel parsed =
        parseMemberStub(
                "record Test(int x) {\n    private String na§\n}",
                SentinelInjectionResult::asMemberNameStub)
            .orElseThrow();

    assertThat(parsed.sentinelContext()).isEqualTo(SentinelContext.VARIABLE_DECLARATION);
    assertThat(parsed.declaredTypeText()).isEqualTo("String");
    assertThat(parsed.enclosingClass()).isEqualTo("Test");
    assertThat(parsed.enclosingMethod()).isNull();
  }

  // At a type position the name stub reads as a constructor, so the two stubs never both answer;
  // after `void` or a type parameter the name can only be a method's, which gets no name in a class
  // body either.
  @Test
  void parseMemberStub_nameStubAtTypePositionOrOutsideRecordBody_answersNothing() {
    assertThat(
            parseMemberStub(
                "record Test(int x) {\n    void na§\n}", SentinelInjectionResult::asMemberNameStub))
        .isEmpty();
    assertThat(
            parseMemberStub(
                "record Test(int x) {\n    <T> T na§\n}",
                SentinelInjectionResult::asMemberNameStub))
        .isEmpty();
    assertThat(
            parseMemberStub(
                "record Test(int x) {\n    public St§\n}",
                SentinelInjectionResult::asMemberNameStub))
        .isEmpty();
    assertThat(
            parseMemberStub(
                "class Test {\n    String na§\n}", SentinelInjectionResult::asMemberNameStub))
        .isEmpty();
    assertThat(
            parseMemberStub(
                "record Test(int x) {\n    void m() {\n        String na§\n    }\n}",
                SentinelInjectionResult::asMemberNameStub))
        .isEmpty();
  }

  private static Optional<ParsedSentinel> parseMemberStub(
      final String source,
      final Function<SentinelInjectionResult, Optional<SentinelInjectionResult>> stubShape) {
    final int cursor = source.indexOf('§');
    final var content = source.replace("§", "");
    final int line = (int) content.substring(0, cursor).chars().filter(c -> c == '\n').count();
    final var stub = stubShape.apply(new SentinelInjector(content).inject(cursor)).orElseThrow();
    try (final var parser = new SourceParser()) {
      return new SentinelParser(parser).parseMemberStub(stub, line, -1, "file:///Test.java");
    }
  }
}
