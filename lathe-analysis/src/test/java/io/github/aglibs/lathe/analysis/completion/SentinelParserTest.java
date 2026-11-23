package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.SourceParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// The member-stub parse (CQ-0065), which full completion reaches only where the first parse lost
// the sentinel: it answers for a record's member start and nowhere else.
class SentinelParserTest {

  @Test
  void parseMemberStub_recordMemberStart_findsTheSentinel() {
    assertThat(parseMemberStub("record Test(int x) {\n    St§\n}")).isPresent();
  }

  @Test
  void parseMemberStub_classMemberStartOrRecordMethodBody_answersNothing() {
    assertThat(parseMemberStub("class Test {\n    St§\n}")).isEmpty();
    assertThat(parseMemberStub("record Test(int x) {\n    void m() {\n        St§\n    }\n}"))
        .isEmpty();
  }

  private static Optional<ParsedSentinel> parseMemberStub(final String source) {
    final int cursor = source.indexOf('§');
    final var content = source.replace("§", "");
    final int line = (int) content.substring(0, cursor).chars().filter(c -> c == '\n').count();
    final var stub = new SentinelInjector(content).inject(cursor).asMemberStub().orElseThrow();
    try (final var parser = new SourceParser()) {
      return new SentinelParser(parser).parseMemberStub(stub, line, -1, "file:///Test.java");
    }
  }
}
