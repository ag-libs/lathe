package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CursorFixture.cursor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.SourceParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
    return sentinelParser.parse(injected, c.lspLine(), 0);
  }

  // ── IMPORT ───────────────────────────────────────────────────────────────

  @Test
  void importDeclaration_regularImport_isImportContext() {
    final var result =
        parse(
            """
        import java.util.Collections.empty§;

        class Foo {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.IMPORT);
    assertThat(result.receiverText()).isEqualTo("java.util.Collections");
    assertThat(result.enclosingClass()).isNull();
    assertThat(result.enclosingMethod()).isNull();
  }

  @Test
  void importDeclaration_staticImport_isStaticImportContext() {
    final var result =
        parse(
            """
        import static java.util.Collections.empty§;

        class Foo {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.STATIC_IMPORT);
    assertThat(result.receiverText()).isEqualTo("java.util.Collections");
  }

  @Test
  void importDeclaration_afterPackageDot_isImportContext() {
    final var result =
        parse(
            """
        import java.§;

        class Foo {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.IMPORT);
    assertThat(result.receiverText()).isEqualTo("java");
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
    assertThat(result.argIndex()).isEqualTo(1);
    assertThat(result.enclosingReceiver()).isEqualTo("map");
    assertThat(result.enclosingMethodName()).isEqualTo("put");
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
    assertThat(result.lambdaParamIndex()).isEqualTo(0);
  }

  // ── VARIABLE_DECLARATION ────────────────────────────────────────────────

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
    assertThat(result.declaredTypeText()).isEqualTo("java.util.ArrayList<String>");
  }

  @Test
  void variableDeclaration_missingType_isVariableDeclarationWithoutTypeText() {
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.List<String> list) {
                list.forEach(§ -> {});
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.VARIABLE_DECLARATION);
    assertThat(result.declaredTypeText()).isNull();
  }

  // ── CLASS / INTERFACE HEADER TYPE REFERENCES ─────────────────────────────
  // Patterns found in Helidon/Dropwizard:
  //   class Builder implements io.helidon.common.Builder§<B, T>
  //   class Foo extends SecurityResponse.SecurityResponseBuilder§<B, T>
  //   interface Foo extends RuntimeType.Api§<X>
  //   class Foo implements SomeOuter.SomeInterface§ (no generics)
  //   record Foo(int x) implements SomeOuter.Interface§ {}
  //   class Foo<T extends TaskConfig.BuilderBase§<?, ?>>

  @Test
  void typeReference_extendsClause_dottedType_isTypeReference() {
    final var result =
        parse(
            """
        class Foo extends java.util.AbstractList§ {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_extendsClause_dottedTypeWithDot_isTypeReference() {
    final var result =
        parse(
            """
        class Foo extends SomeOuter.SomeBase§ {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_extendsClause_dottedTypeBeforeGenerics_isTypeReference() {
    // cursor before the '<' — real pattern: class Builder extends
    // SecurityResponse.ResponseBuilder§<B, T>
    final var result =
        parse(
            """
        class Foo extends SomeOuter.SomeBase§<String> {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_implementsClause_dottedType_isTypeReference() {
    // real pattern: class Foo implements SomeOuter.SomeInterface (no generics)
    final var result =
        parse(
            """
        class Foo implements SomeOuter.SomeInterface§ {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_implementsClause_qualifiedNameBeforeGenerics_isTypeReference() {
    // real pattern: class Builder implements io.helidon.common.Builder§<B, T>
    final var result =
        parse(
            """
        class Foo implements io.some.Interface§<String> {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_implementsClause_genericTypeArg_isTypeReference() {
    // cursor inside generic type arg — '<' to the left sets EXPRESSION context
    // real pattern: class RoleValidator implements AbacValidator<RoleValidator.RoleConfig§>
    final var result =
        parse(
            """
        class Foo implements java.util.function.Function<String, Bar.Baz§> {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_interfaceExtendsClause_dottedType_isTypeReference() {
    // real pattern: interface Foo extends RuntimeType.Api§<X>
    final var result =
        parse(
            """
        interface Foo extends SomeOuter.BaseInterface§ {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_typeParameterBoundDotted_isTypeReference() {
    // real pattern: class Foo<T extends TaskConfig.BuilderBase§<?, ?>>
    final var result =
        parse(
            """
        class Foo<T extends SomeOuter.SomeBase§> {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_recordImplementsClause_isTypeReference() {
    // real pattern: record Foo(int x) implements SomeOuter.Interface§ {}
    final var result =
        parse(
            """
        record Foo(int x) implements SomeOuter.Interface§ {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_genericTypeArg_inLocalVar_isTypeReference() {
    final var result =
        parse(
            """
        class Foo {
            void m() {
                java.util.List<Str§> list;
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  @Test
  void typeReference_localVariableType_isTypeReference() {
    final var result =
        parse(
            """
        class Foo {
            void m() {
                ArrayL§ local;
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }

  @Test
  void typeReference_extendsClause_simpleName_isTypeReference() {
    final var result =
        parse(
            """
        class Foo extends AbstractL§ {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void typeReference_implementsClause_simpleName_isTypeReference() {
    final var result =
        parse(
            """
        class Foo implements Runnabl§ {
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }

  @Test
  void memberAccess_cursorAtTokenStart_isValid() {
    final var result =
        parse(
            """
        class Foo {
            void m(java.util.List<String> list) {
                list.§values();
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.MEMBER_ACCESS);
    assertThat(result.receiverText()).isEqualTo("list");
  }

  @Test
  void typeReference_innerClassExtendsClause_isTypeReference() {
    // real pattern: inner Builder class extends Outer.Builder§<B>
    final var result =
        parse(
            """
        class Outer {
            static class Inner extends Outer.Base§<String> {
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.TYPE_REFERENCE);
  }
}
