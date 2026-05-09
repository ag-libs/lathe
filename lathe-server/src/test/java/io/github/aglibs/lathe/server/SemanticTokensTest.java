package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Sample.java (0-based lines, after Javadoc additions):
//  17:   @Retention(RetentionPolicy.RUNTIME)     RUNTIME=29
//  18:   @Target(ElementType.METHOD)             METHOD=22
//  23:   enum Status {
//  24:     ACTIVE,                                ACTIVE=4
//  25:     INACTIVE                               INACTIVE=4
//  28:   private static final String PREFIX       PREFIX=30
//  73:     return "hello".toUpperCase(ENGLISH)    ENGLISH=31
//  88:   public <T> T identity(final T value)    T(decl)=10  T(ret)=13  T(param)=30
// 101:         .filter(s -> s.startsWith(PREFIX)) PREFIX=34
// 106:   public static String staticHelper(...)   staticHelper=23
// 109:     return PREFIX + upper;                 PREFIX=11
// 123:   @Deprecated
// 124:   public String oldFormat(...)             oldFormat=16
// 129:     return oldFormat(value);               oldFormat=11
class SemanticTokensTest extends SampleFixture {

  List<SemanticToken> tokens;

  @BeforeEach
  void scanTokens() throws IOException {
    tokens = SemanticTokensScanner.scan(trees, cu);
  }

  @Nested
  class Annotations {

    @Test
    void class_annotation_is_annotation_type() {
      // @SuppressWarnings at 0-based line 14: @ col 0, name at col 1
      assertToken(14, 1, "annotation");
    }

    @Test
    void method_annotation_is_annotation_type() {
      // @Logged at 0-based line 98: @ col 2, name at col 3
      assertToken(98, 3, "annotation");
    }

    @Test
    void deprecated_annotation_is_annotation_type() {
      // @Deprecated at 0-based line 123: @ col 2, name at col 3
      assertToken(123, 3, "annotation");
    }
  }

  @Nested
  class Declarations {

    @Test
    void typeParam_decl_is_typeParameter_with_declaration() {
      assertToken(88, 10, "typeParameter", "declaration");
    }

    @Test
    void enumConst_decl_is_enumMember_with_declaration() {
      assertToken(25, 4, "enumMember", "declaration");
      assertToken(24, 4, "enumMember", "declaration");
    }

    @Test
    void staticField_decl_has_static_modifier() {
      assertToken(28, 30, "property", "declaration", "static");
    }

    @Test
    void staticMethod_decl_has_static_modifier() {
      assertToken(106, 23, "method", "declaration", "static");
    }

    @Test
    void deprecated_method_decl_has_deprecated_modifier() {
      assertToken(124, 16, "method", "declaration", "deprecated");
    }
  }

  @Nested
  class Usages {

    @Test
    void typeParam_in_return_type_is_typeParameter() {
      assertToken(88, 13, "typeParameter");
    }

    @Test
    void typeParam_in_param_type_is_typeParameter() {
      assertToken(88, 30, "typeParameter");
    }

    @Test
    void enumConst_in_field_access_is_enumMember() {
      // Status.ACTIVE
      assertToken(120, 18, "enumMember");
    }

    @Test
    void enumConst_in_annotation_arg_is_enumMember() {
      // RetentionPolicy.RUNTIME and ElementType.METHOD
      assertToken(17, 29, "enumMember");
      assertToken(18, 22, "enumMember");
    }

    @Test
    void staticField_usage_is_property_with_static() {
      // ENGLISH from static import
      assertToken(73, 31, "property", "static");
      // PREFIX in summarize
      assertToken(101, 34, "property", "static");
      // PREFIX in staticHelper
      assertToken(109, 11, "property", "static");
    }

    @Test
    void deprecated_method_usage_has_deprecated_modifier() {
      assertToken(129, 11, "method", "deprecated");
    }
  }

  @Nested
  class NoToken {

    @Test
    void regular_method_decl_has_no_token() {
      // "getName" — instance method, no interesting modifiers
      assertNoToken(48, 16);
    }

    @Test
    void instance_field_usage_has_no_token() {
      // "name" in "return name;"
      assertNoToken(49, 11);
    }

    @Test
    void parameter_has_no_token() {
      // "value" in "public String run(final String value)"
      assertNoToken(67, 33);
    }

    @Test
    void local_variable_has_no_token() {
      // "trimmed" in "var trimmed = input.strip();"
      assertNoToken(107, 8);
    }

    @Test
    void type_reference_has_no_token() {
      // "String" return type of getName
      assertNoToken(48, 9);
    }
  }

  @Nested
  class Encoding {

    int[] encoded;

    @BeforeEach
    void encode() {
      encoded = SemanticTokensScanner.encode(tokens);
    }

    @Test
    void produces_five_ints_per_token() {
      assertThat(encoded.length).isEqualTo(tokens.size() * 5);
    }

    @Test
    void first_token_delta_line_equals_its_absolute_line() {
      assertThat(encoded.length).isGreaterThanOrEqualTo(5);
      assertThat(encoded[0]).isEqualTo(tokens.getFirst().line());
    }

    @Test
    void token_type_index_is_within_legend_bounds() {
      for (int i = 3; i < encoded.length; i += 5) {
        assertThat(encoded[i]).isBetween(0, SemanticTokensScanner.TOKEN_TYPES.size() - 1);
      }
    }
  }

  private void assertToken(
      final int line, final int character, final String type, final String... modifiers) {
    final var tok = tokenAt(line, character);
    assertThat(tok).as("token at %d:%d", line, character).isNotNull();
    assertThat(tok.type()).isEqualTo(type);
    if (modifiers.length > 0) {
      assertThat(tok.modifiers()).containsExactlyInAnyOrder(modifiers);
    } else {
      assertThat(tok.modifiers()).isEmpty();
    }
  }

  private void assertNoToken(final int line, final int character) {
    assertThat(tokenAt(line, character)).as("no token at %d:%d", line, character).isNull();
  }

  private SemanticToken tokenAt(final int line, final int character) {
    return tokens.stream()
        .filter(
            t ->
                t.line() == line
                    && t.character() <= character
                    && character < t.character() + t.length())
        .findFirst()
        .orElse(null);
  }
}
