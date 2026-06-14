package io.github.aglibs.lathe.server.analysis;

// Sample.java DocHelper positions (0-based lines):
//
// 115:   public static class DocHelper {                     DocHelper=22
// 142:       var instance = new DocHelper();                 DocHelper=25
// 143:       var max = MAX;                                  MAX=16
// 144:       var greeting = greet();                         greet=21
// 145:       var items = repeat("hi", 3);                    repeat=18
// 146:       var upper = repeat("hi", 3).stream()...greet()  greet=52

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  @Test
  void hover_parameter_showsTypeAndName() {
    // "value" in "Objects.requireNonNull(value)" on line 51
    final var md = hoverAt(50, 27);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("String").contains("value");
  }

  @Test
  void hover_localVariable_showsTypeAndName() {
    // "trimmed" in "var trimmed = input.strip()" on line 90
    final var md = hoverAt(89, 8);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("String").contains("trimmed");
  }

  @Test
  void hover_lambdaParameter_showsTypeAndName() {
    // "s" parameter declaration in ".map(s -> s.toUpperCase())" on line 60
    final var md = hoverAt(59, 30);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("String").contains("s");
  }

  @Test
  void hover_annotationType_showsKindAndName() {
    // "Logged" in "@Logged("summarize")" on line 81
    final var md = hoverAt(80, 3);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("@interface").contains("Logged");
  }

  @Test
  void hover_enumConstant_showsTypeAndName() {
    // "ACTIVE" in "return Status.ACTIVE" on line 103
    final var md = hoverAt(102, 18);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("Status").contains("ACTIVE");
  }

  // --- class-file dependency ---

  @Test
  void hover_classFileDependency_showsSourceParameterNames(@TempDir final Path tmpDir)
      throws Exception {
    try (final var fixture = new ClassFileFixture(tmpDir)) {
      final var source =
          """
          class Test {
              void caller() { new Greeter().greet("x", 1); }
          }
          """;
      fixture.session().compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var pos = SourceLocator.offsetToPosition(source, source.indexOf("greet"));
      final var request =
          new SourceFeatureRequest(
              TempSourceCompiler.TEST_URI,
              source,
              pos,
              List.of(fixture.srcDir()),
              WorkspaceManifest.empty());
      final var hover = fixture.session().hover(request);

      assertThat(hover).isNotNull();
      assertThat(hover.getContents().getRight().getValue()).contains("String name", "int count");
    }
  }
}
