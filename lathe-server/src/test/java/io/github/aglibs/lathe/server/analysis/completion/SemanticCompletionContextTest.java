package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import javax.lang.model.type.TypeMirror;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticCompletionContextTest {

  private CompletionFixture fixture;

  @BeforeEach
  void setup() {
    fixture = new CompletionFixture();
  }

  @AfterEach
  void teardown() {
    fixture.close();
  }

  @Test
  void expected_variableInitializer_resolvesDeclaringType() {
    final SemanticCompletionContext context =
        fixture.semanticContext(
            """
            class Test {
              void m() {
                String s = §"dummy";
              }
            }
            """);

    assertExpectedType(context, "java.lang.String");
  }

  @Test
  void expected_methodArgument_resolvesParameterType() {
    final SemanticCompletionContext context =
        fixture.semanticContext(
            """
            class Test {
              void accept(String value) {}

              void m() {
                accept(§);
              }
            }
            """);

    assertExpectedType(context, "java.lang.String");
  }

  @Test
  void expected_zeroParamMethod_resolvesNoSlot() {
    final SemanticCompletionContext context =
        fixture.semanticContext(
            """
            class Test {
              void noArgs() {}

              void m() {
                noArgs(§);
              }
            }
            """);

    assertThat(context.expectedValue()).isInstanceOf(ExpectedValue.NoSlot.class);
  }

  @Test
  void expected_unknownExpression_resolvesUnknown() {
    final SemanticCompletionContext context =
        fixture.semanticContext(
            """
            class Test {
              void m() {
                §
              }
            }
            """);

    assertThat(context.expectedValue()).isInstanceOf(ExpectedValue.Unknown.class);
  }

  private static void assertExpectedType(
      final SemanticCompletionContext context, final String expectedType) {
    assertThat(context.expectedValue())
        .isInstanceOfSatisfying(
            ExpectedValue.Type.class,
            (final ExpectedValue.Type type) ->
                assertThat(typeName(type.type())).isEqualTo(expectedType));
  }

  private static String typeName(final TypeMirror type) {
    return type.toString();
  }
}
