package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CamelCaseMatcherTest {

  @Test
  void matches_pureInitials_findsAbbreviation() {
    assertThat(CamelCaseMatcher.matches("ASF", "AbstractServerFactory")).isTrue();
  }

  @Test
  void matches_pureInitials_wrongOrderReturnsFalse() {
    assertThat(CamelCaseMatcher.matches("FSA", "AbstractServerFactory")).isFalse();
  }

  @Test
  void matches_hyphenatedAbbreviation_skipsVowelsWithinHump() {
    assertThat(CamelCaseMatcher.matches("Mgr", "TaskManager")).isTrue();
  }

  @Test
  void matches_partialWordPerHump_findsTarget() {
    assertThat(CamelCaseMatcher.matches("TaskMgr", "TaskManager")).isTrue();
  }

  @Test
  void matches_infixHumps_skipsLeadingHump() {
    assertThat(CamelCaseMatcher.matches("ServerFactory", "AbstractServerFactory")).isTrue();
  }

  @Test
  void matches_exactSimpleName_returnsTrue() {
    assertThat(CamelCaseMatcher.matches("TaskManager", "TaskManager")).isTrue();
  }

  @Test
  void matches_noHumpOverlap_returnsFalse() {
    assertThat(CamelCaseMatcher.matches("Widget", "TaskManager")).isFalse();
  }

  @Test
  void matches_queryLongerThanCandidate_returnsFalse() {
    assertThat(CamelCaseMatcher.matches("TaskManagerFactory", "TaskManager")).isFalse();
  }

  @Test
  void matches_emptyQuery_returnsFalse() {
    assertThat(CamelCaseMatcher.matches("", "TaskManager")).isFalse();
  }

  @Test
  void matches_lowercaseHumpAnchor_isCaseInsensitive() {
    assertThat(CamelCaseMatcher.matches("taskMgr", "TaskManager")).isTrue();
  }

  @Test
  void matches_undifferentiatedLowercaseQuery_doesNotSpanMultipleHumps() {
    assertThat(CamelCaseMatcher.matches("taskmgr", "TaskManager")).isFalse();
  }

  @Test
  void matches_digitBoundary_treatsDigitAsHumpStart() {
    assertThat(CamelCaseMatcher.matches("H2C", "Http2Client")).isTrue();
  }
}
