package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.AttributedExpression;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.TempSourceCompiler;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class SymbolToJdiTest {

  private static final String SAMPLE =
      """
      class Sample {
        static int shared = 1;
        int field = 3;

        void run(String arg) {
          int local = 5;
          System.out.println(arg);
        }
      }
      """;

  // The println line (1-based 7) is the breakpoint; local, arg, field, and shared are in scope.
  private static final int LINE = 7;

  private static AttributedExpression attribute(
      final SourceAnalysisSession session, final String expression) {
    return session
        .attributeExpression(TempSourceCompiler.TEST_URI, SAMPLE, LINE, expression)
        .orElseThrow();
  }

  private static void withRef(final String expression, final Consumer<JdiRef> assertions) {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final AttributedExpression attr = attribute(session, expression);
      final var element = attr.trees().getElement(attr.expression());
      assertions.accept(SymbolToJdi.toRef(element, attr.elements()).orElseThrow());
    }
  }

  @Test
  void toRef_local_matchesByName() {
    withRef("local", ref -> assertThat(ref).isEqualTo(new JdiRef.Local("local")));
  }

  @Test
  void toRef_parameter_matchesByName() {
    withRef("arg", ref -> assertThat(ref).isEqualTo(new JdiRef.Local("arg")));
  }

  @Test
  void toRef_instanceField_carriesDeclaringTypeAndName() {
    withRef("field", ref -> assertThat(ref).isEqualTo(new JdiRef.Field("Sample", "field", false)));
  }

  @Test
  void toRef_staticField_isMarkedStatic() {
    withRef("shared", ref -> assertThat(ref).isEqualTo(new JdiRef.Field("Sample", "shared", true)));
  }

  @Test
  void toRef_nestedType_usesDollarBinaryName() {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final AttributedExpression attr = attribute(session, "field");
      final var nested = attr.elements().getTypeElement("java.util.Map.Entry");
      assertThat(SymbolToJdi.toRef(nested, attr.elements()))
          .contains(new JdiRef.Type("java.util.Map$Entry"));
    }
  }

  @Test
  void toRef_method_isUnsupportedInV1() {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final AttributedExpression attr = attribute(session, "arg.length()");
      final var element = attr.trees().getElement(attr.expression());
      assertThat(SymbolToJdi.toRef(element, attr.elements())).isEmpty();
    }
  }

  @Test
  void toRef_null_returnsEmpty() {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final AttributedExpression attr = attribute(session, "field");
      assertThat(SymbolToJdi.toRef(null, attr.elements())).isEmpty();
    }
  }
}
