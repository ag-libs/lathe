package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Each case as IntelliJ's Go to Class answers it (EG-129).
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
  void matches_wordPrefixes_findsTarget() {
    assertThat(CamelCaseMatcher.matches("TaskMan", "TaskManager")).isTrue();
    assertThat(CamelCaseMatcher.matches("TaMa", "TaskManager")).isTrue();
    assertThat(CamelCaseMatcher.matches("TMan", "TaskManager")).isTrue();
    assertThat(CamelCaseMatcher.matches("TaMx", "TaskManager")).isFalse();
  }

  // EG-129: a query letter continues its word or starts a later one, never lands inside a word, so
  // an abbreviation that drops letters (Mgr) finds nothing, as in IntelliJ.
  @Test
  void matches_letterInsideAWord_returnsFalse() {
    assertThat(CamelCaseMatcher.matches("Pet", "I18nPropertiesSyncTest")).isFalse();
    assertThat(CamelCaseMatcher.matches("SerSH", "ShakespearePlaysScrabbleWithHelidon")).isFalse();
    assertThat(CamelCaseMatcher.matches("Mgr", "TaskManager")).isFalse();
    assertThat(CamelCaseMatcher.matches("TaskMgr", "TaskManager")).isFalse();
    assertThat(CamelCaseMatcher.matches("Pet", "PetController")).isTrue();
    assertThat(CamelCaseMatcher.matches("SerSH", "ServerShutdownHandler")).isTrue();
  }

  @Test
  void matches_skippedWords_findsTarget() {
    assertThat(CamelCaseMatcher.matches("SSH", "ShakespearePlaysScrabbleWithHelidon")).isTrue();
    assertThat(CamelCaseMatcher.matches("ServerFactory", "AbstractServerFactory")).isTrue();
    assertThat(CamelCaseMatcher.matches("SFX", "AbstractServerFactory")).isFalse();
  }

  @Test
  void matches_exactSimpleName_returnsTrue() {
    assertThat(CamelCaseMatcher.matches("TaskManager", "TaskManager")).isTrue();
  }

  @Test
  void matches_noWordOverlap_returnsFalse() {
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

  // A lowercase query has no capitals to mark its words, so any letter may start one.
  @Test
  void matches_lowercaseQuery_startsWordsAnywhere() {
    assertThat(CamelCaseMatcher.matches("taskman", "TaskManager")).isTrue();
    assertThat(CamelCaseMatcher.matches("tm", "TaskManager")).isTrue();
    assertThat(CamelCaseMatcher.matches("oc", "OwnerController")).isTrue();
    assertThat(CamelCaseMatcher.matches("Oc", "OwnerController")).isTrue();
    assertThat(CamelCaseMatcher.matches("taskmgr", "TaskManager")).isFalse();
  }

  // A capital past the first letter marks the query's words: its lowercase letters continue one.
  @Test
  void matches_queryMarkingItsWords_lowercaseLettersContinueAWord() {
    assertThat(CamelCaseMatcher.matches("taskMan", "TaskManager")).isTrue();
    assertThat(CamelCaseMatcher.matches("oCT", "OwnerControllerTest")).isTrue();
    assertThat(CamelCaseMatcher.matches("OcT", "OwnerControllerTest")).isFalse();
    assertThat(CamelCaseMatcher.matches("WsC", "WebSocketClient")).isFalse();
    // Case is ignored, so a capital still continues a run.
    assertThat(CamelCaseMatcher.matches("OCT", "Octopus")).isTrue();
  }

  @Test
  void matches_digits_eachStartsAWord() {
    assertThat(CamelCaseMatcher.matches("H2C", "Http2Client")).isTrue();
    assertThat(CamelCaseMatcher.matches("h2c", "Http2client")).isTrue();
    assertThat(CamelCaseMatcher.matches("A28", "Aes128Cipher")).isTrue();
    assertThat(CamelCaseMatcher.matches("hc", "Http2client")).isFalse();
  }

  @Test
  void matches_acronym_startsAtItsLastCapitalBeforeLowercase() {
    assertThat(CamelCaseMatcher.matches("UC", "URLConnection")).isTrue();
    assertThat(CamelCaseMatcher.matches("urlcon", "URLConnection")).isTrue();
    assertThat(CamelCaseMatcher.matches("ULC", "URLConnection")).isFalse();
  }

  @Test
  void matches_separator_startsAWordAndIsSkippedTo() {
    assertThat(CamelCaseMatcher.matches("fb", "foo_bar")).isTrue();
    assertThat(CamelCaseMatcher.matches("mes_", "ErrorMessages_ca")).isTrue();
    assertThat(CamelCaseMatcher.matches("fb_", "foo_bar")).isFalse();
  }
}
