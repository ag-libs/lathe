package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.lang.model.type.TypeMirror;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CompletionCandidateRankerTest {

  private static CompletionCandidateFixture fixture;
  private static TypeMirror stringType;

  @BeforeAll
  static void setup() {
    fixture =
        new CompletionCandidateFixture(
            """
            class Fixture {
              String returnsString() { return ""; }
              int returnsInt() { return 0; }
              void returnsVoid() {}
              String stringField = "";
              int intField = 0;
            }
            """);
    stringType = fixture.type("java.lang.String");
  }

  @AfterAll
  static void teardown() {
    fixture.close();
  }

  @Test
  void ranker_expectedString_assignableMethodRanksBeforeNonAssignable() {
    final CompletionCandidate returnsString = fixture.method("returnsString");
    final CompletionCandidate returnsInt = fixture.method("returnsInt");

    final var ranked =
        CompletionCandidateRanker.rank(
            List.of(returnsInt, returnsString), fixture.expected(stringType));

    final var labels = ranked.stream().map(r -> r.candidate().name()).toList();
    assertThat(labels.indexOf("returnsString")).isLessThan(labels.indexOf("returnsInt"));
  }

  @Test
  void ranker_expectedString_assignableFieldRanksBeforeNonAssignable() {
    final CompletionCandidate stringField = fixture.field("stringField");
    final CompletionCandidate intField = fixture.field("intField");

    final var ranked =
        CompletionCandidateRanker.rank(
            List.of(intField, stringField), fixture.expected(stringType));

    final var labels = ranked.stream().map(r -> r.candidate().name()).toList();
    assertThat(labels.indexOf("stringField")).isLessThan(labels.indexOf("intField"));
  }

  @Test
  void ranker_expectedType_voidMethodExcluded() {
    final CompletionCandidate returnsVoid = fixture.method("returnsVoid");
    final CompletionCandidate returnsString = fixture.method("returnsString");

    final var ranked =
        CompletionCandidateRanker.rank(
            List.of(returnsVoid, returnsString), fixture.expected(stringType));

    final var names = ranked.stream().map(r -> r.candidate().name()).toList();
    assertThat(names).doesNotContain("returnsVoid");
    assertThat(names).contains("returnsString");
  }

  @Test
  void ranker_valueContext_voidMethodExcluded() {
    final CompletionCandidate returnsVoid = fixture.method("returnsVoid");
    final CompletionCandidate returnsString = fixture.method("returnsString");

    final var ranked =
        CompletionCandidateRanker.rank(List.of(returnsVoid, returnsString), fixture.valueContext());

    final var names = ranked.stream().map(r -> r.candidate().name()).toList();
    assertThat(names).doesNotContain("returnsVoid");
    assertThat(names).contains("returnsString");
  }

  @Test
  void ranker_expectedType_objectMethodExcluded() {
    final CompletionCandidate waitMethod = fixture.noArgMethod("java.lang.Object", "wait");
    final CompletionCandidate returnsString = fixture.method("returnsString");

    final var ranked =
        CompletionCandidateRanker.rank(
            List.of(waitMethod, returnsString), fixture.expected(stringType));

    final var names = ranked.stream().map(r -> r.candidate().name()).toList();
    assertThat(names).doesNotContain("wait");
    assertThat(names).contains("returnsString");
  }

  @Test
  void ranker_noExpectedType_objectMethodDemoted() {
    final CompletionCandidate waitMethod = fixture.noArgMethod("java.lang.Object", "wait");
    final CompletionCandidate returnsString = fixture.method("returnsString");

    final var ranked =
        CompletionCandidateRanker.rank(List.of(waitMethod, returnsString), fixture.unknown());

    final var names = ranked.stream().map(r -> r.candidate().name()).toList();
    assertThat(names).contains("wait", "returnsString");
    assertThat(ranked.stream().filter(r -> "wait".equals(r.candidate().name())).findFirst())
        .hasValueSatisfying(r -> assertThat(r.sortText()).startsWith("9_"));
  }

  @Test
  void ranker_noSlot_allCandidatesDropped() {
    final var candidates =
        List.of(
            fixture.method("returnsString"),
            fixture.method("returnsInt"),
            fixture.field("stringField"));

    final var ranked = CompletionCandidateRanker.rank(candidates, fixture.noSlot());

    assertThat(ranked).isEmpty();
  }
}
