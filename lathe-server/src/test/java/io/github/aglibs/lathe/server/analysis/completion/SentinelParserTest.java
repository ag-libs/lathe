package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CursorFixture.cursor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.SourceParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class SentinelParserTest {

  private static SourceParser sourceParser;
  private static SentinelParser sentinelParser;

  @BeforeAll
  static void setup() {
    sourceParser = new SourceParser();
    sentinelParser = new SentinelParser(sourceParser);
  }

  @AfterAll
  static void teardown() throws Exception {
    sourceParser.close();
  }

  private static ParsedSentinel parse(final String markedSource) {
    final var c = cursor(markedSource);
    final var injected = new SentinelInjector(c.content()).inject(c.offset());
    final int lspLine =
        (int) c.content().substring(0, c.offset()).chars().filter(ch -> ch == '\n').count();
    return sentinelParser.parse(injected, lspLine, 0);
  }

  // ── MODULE_DIRECTIVE ─────────────────────────────────────────────────────

  @Test
  void moduleDirective_requires_isModuleDirective() {
    final var result =
        parse(
            """
        module foo {
            requires io.helidon.dbclient.metrics.§
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.MODULE_DIRECTIVE);
    assertThat(result.receiverText()).isEqualTo("io.helidon.dbclient.metrics");
    assertThat(result.enclosingClass()).isNull();
    assertThat(result.enclosingMethod()).isNull();
  }

  @Test
  void moduleDirective_exports_isModuleDirective() {
    final var result =
        parse(
            """
        module foo {
            exports io.helidon.dbclient.§
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.MODULE_DIRECTIVE);
    assertThat(result.receiverText()).isEqualTo("io.helidon.dbclient");
  }

  @Disabled("Not implemented yet")
  @Test
  void moduleDirective_dotAfterTransitiveKeyword_isInvalid() {
    final var result =
        parse(
            """
        module foo {
            requires transitive.§
        }""");
    assertThat(result.valid()).isFalse();
  }

  // ── MEMBER_ACCESS ────────────────────────────────────────────────────────

  @Test
  void memberAccess_objectReceiver_isMemberAccess() {
    final var result =
        parse(
            """
        class Foo {
            void m(Object obj) {
                obj.§
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.MEMBER_ACCESS);
    assertThat(result.receiverText()).isEqualTo("obj");
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  @Test
  void memberAccess_chainedCall_isMemberAccess() {
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.List<String> list) {
                list.stream().filter§
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.MEMBER_ACCESS);
    assertThat(result.receiverText()).isEqualTo("list.stream()");
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  // ── SIMPLE_NAME ──────────────────────────────────────────────────────────

  @Test
  void simpleName_bareIdentifier_isSimpleName() {
    final var result =
        parse(
            """
        class Foo {
            void m() {
                Str§
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.SIMPLE_NAME);
    assertThat(result.receiverText()).isNull();
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  // ── TYPE_REFERENCE ───────────────────────────────────────────────────────

  @Test
  void typeReference_methodParameter_isTypeReference() {
    // EXPRESSION context: backward scan hits '(' of the param list
    final var result =
        parse(
            """
        class Foo {
            void m(ArrayL§ list) {}
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
  }

  @Test
  void typeReference_wildcardBound_isTypeReference() {
    // EXPRESSION context: backward scan hits '(' of the param list, so no ';' inside '<>'
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.List<? extends Bar§> list) {}
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
  }

  @Disabled(
      """
      Two issues: (1) STATEMENT context causes injector to insert ';' inside '<>', \
      breaking the parse; (2) TypeParameterTree is absent from classifySentinel \
      so even a correctly-injected snippet would return SIMPLE_NAME instead of TYPE_REFERENCE""")
  @Test
  void typeReference_typeParameterBound_isTypeReference() {
    final var result =
        parse(
            """
        class Foo<T extends Bar§> {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_castExpression_isTypeReference() {
    // EXPRESSION context: backward scan hits '(' of the cast
    final var result =
        parse(
            """
        class Foo {
            void m(Object obj) {
                ((ArrayL§) obj).size();
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  // ── ARGUMENT_POSITION ────────────────────────────────────────────────────

  @Test
  void argumentPosition_singleArg_isArgumentPosition() {
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.List<String> list) {
                list.forEach(str§);
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.ARGUMENT_POSITION);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  @Test
  void argumentPosition_multipleArgs_isArgumentPosition() {
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.Map<String, String> map) {
                map.put(key, val§);
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.ARGUMENT_POSITION);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  @Disabled("argIndex / enclosingReceiver / enclosingMethodName not in ParsedSentinel yet")
  @Test
  void argumentPosition_argIndex_isExtracted() {
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.Map<String, String> map) {
                map.put(key, val§);
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.ARGUMENT_POSITION);
    // assertThat(result.argIndex()).isEqualTo(1);
    // assertThat(result.enclosingReceiver()).isEqualTo("map");
    // assertThat(result.enclosingMethodName()).isEqualTo("put");
  }

  // ── CONSTRUCTOR_CALL ─────────────────────────────────────────────────────

  @Test
  void constructorCall_simpleNew_isConstructorCall() {
    final var result =
        parse(
            """
        class Foo {
            void m() {
                new ArrayL§();
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.CONSTRUCTOR_CALL);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  // ── ANNOTATION_CONTEXT ───────────────────────────────────────────────────

  @Test
  void annotationContext_classAnnotation_isAnnotationContext() {
    final var result =
        parse(
            """
        @Dep§
        class Foo {}""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.ANNOTATION_CONTEXT);
  }

  @Test
  void annotationContext_methodAnnotation_isAnnotationContext() {
    final var result =
        parse(
            """
        class Foo {
            @Ove§
            void m() {}
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.ANNOTATION_CONTEXT);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
  }

  // ── LAMBDA_BODY ──────────────────────────────────────────────────────────

  @Test
  void lambdaBody_expressionLambda_isLambdaBody() {
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.List<String> list) {
                list.forEach(s -> s.str§);
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.LAMBDA_BODY);
    assertThat(result.receiverText()).isEqualTo("s");
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  @Disabled("lambdaParamIndex not in ParsedSentinel yet")
  @Test
  void lambdaBody_paramIndex_isExtracted() {
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.List<String> list) {
                list.forEach(s -> s.str§);
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.LAMBDA_BODY);
    // assertThat(result.lambdaParamIndex()).isEqualTo(0);
  }

  // ── VARIABLE_DECLARATION (unsupported) ───────────────────────────────────

  @Disabled(
      """
      SentinelFinder.visitVariable not implemented — sentinel as variable name \
      is a Name on VariableTree, not an IdentifierTree; SentinelFinder never visits it""")
  @Test
  void variableDeclaration_localVar_isVariableDeclaration() {
    final var result =
        parse(
            """
        class Foo {
            void m() {
                java.util.ArrayList<String> myVar§;
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.VARIABLE_DECLARATION);
    // assertThat(result.declaredTypeText()).isEqualTo("java.util.ArrayList<String>");
  }
}
