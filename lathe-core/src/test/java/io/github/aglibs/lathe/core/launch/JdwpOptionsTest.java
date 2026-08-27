package io.github.aglibs.lathe.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class JdwpOptionsTest {

  @Test
  void agentArg_enabledPort_carriesTransportServerSuspendAndAddress() {
    assertThat(new JdwpOptions(5005).agentArg())
        .isEqualTo("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005");
  }

  @Test
  void enabled_positivePort_isTrue() {
    assertThat(new JdwpOptions(5005).enabled()).isTrue();
  }

  @Test
  void enabled_none_isFalse() {
    assertThat(JdwpOptions.NONE.enabled()).isFalse();
  }

  @Test
  void isListeningLine_bannerForConfiguredPort_matches() {
    assertThat(
            new JdwpOptions(37591)
                .isListeningLine("Listening for transport dt_socket at address: 37591"))
        .isTrue();
  }

  @Test
  void isListeningLine_bannerForDifferentPort_doesNotMatch() {
    assertThat(
            new JdwpOptions(37591)
                .isListeningLine("Listening for transport dt_socket at address: 5005"))
        .isFalse();
  }

  @Test
  void isListeningLine_unrelatedLineEndingInThePort_doesNotMatch() {
    // Ends with the port but is not the banner: the substring guard, not endsWith alone, must gate.
    assertThat(new JdwpOptions(37591).isListeningLine("\tat com.example.App.main(App.java:37591)"))
        .isFalse();
  }
}
