package io.github.aglibs.lathe.server;

// Sample.java DocHelper positions (0-based lines):
//
// 115:   public static class DocHelper {                     DocHelper=22
// 142:       var instance = new DocHelper();                 DocHelper=25
// 143:       var max = MAX;                                  MAX=16
// 144:       var greeting = greet();                         greet=21
// 145:       var items = repeat("hi", 3);                    repeat=18
// 146:       var upper = repeat("hi", 3).stream()...greet()  greet=52

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HoverTest extends SampleFixture {

  @Test
  void hover_type_showsClassJavadoc() {
    // "DocHelper" in "var instance = new DocHelper()"
    final var md = hoverAt(142, 25);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("class DocHelper").contains("Utility for hover Javadoc tests");
  }

  @Test
  void hover_field_showsFieldJavadoc() {
    // "MAX" in "var max = MAX"
    final var md = hoverAt(143, 16);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("MAX").contains("maximum number of items allowed");
  }

  @Test
  void hover_method_showsMethodJavadoc() {
    // "greet" in "var greeting = greet()"
    final var md = hoverAt(144, 21);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("greet").contains("Returns a greeting message");
  }

  @Test
  void hover_genericMethod_showsJavadoc() {
    // "repeat" in "var items = repeat("hi", 3)"
    final var md = hoverAt(145, 18);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("repeat").contains("Repeats the given value");
  }

  @Test
  void hover_methodInLambda_showsJavadoc() {
    // "greet" inside ".map(s -> greet())"
    final var md = hoverAt(146, 52);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("greet").contains("Returns a greeting message");
  }
}
