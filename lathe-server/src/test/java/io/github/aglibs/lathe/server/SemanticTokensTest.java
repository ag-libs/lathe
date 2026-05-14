package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Sample.java (0-based lines):
//  16:   @Retention(RetentionPolicy.RUNTIME)     RUNTIME=29
//  17:   @Target(ElementType.METHOD)             METHOD=22
//  22:   enum Status {
//  23:     ACTIVE, INACTIVE                       ACTIVE=4  INACTIVE=12
//  26:   private static final String PREFIX       PREFIX=30
//  54:     return "hello".toUpperCase(ENGLISH)    ENGLISH=31
//  69:   public <T> T identity(final T value)    T(decl)=10  T(ret)=13  T(param)=30
//  82:         .filter(s -> s.startsWith(PREFIX)) PREFIX=34
//  87:   public static String staticHelper(...)   staticHelper=23
//  90:     return PREFIX + upper;                 PREFIX=11
// 104:   @Deprecated
// 105:   public String oldFormat(...)             oldFormat=16
// 110:     return oldFormat(value);               oldFormat=11
class SemanticTokensTest extends SampleFixture {

  List<SemanticToken> tokens;

  @BeforeEach
  void scanTokens() throws IOException {
    tokens = TokenScanner.scan(trees, cu);
  }

  @Nested
  class Annotations {

    @Test
    void class_annotation_is_annotation_type() {
      // @SuppressWarnings at 0-based line 13: @ col 0, name at col 1
      assertToken(13, 1, "annotation");
    }

    @Test
    void method_annotation_is_annotation_type() {
      // @Logged at 0-based line 80: @ col 2, name at col 3
      assertToken(80, 3, "annotation");
    }

    @Test
    void deprecated_annotation_is_annotation_type() {
      // @Deprecated at 0-based line 105: @ col 2, name at col 3
      assertToken(105, 3, "annotation");
    }
  }

  @Nested
  class Declarations {

    @Test
    void typeParam_decl_is_typeParameter_with_declaration() {
      assertToken(70, 10, "typeParameter", "declaration");
    }

    @Test
    void enumConst_decl_is_enumMember_with_declaration() {
      assertToken(23, 4, "enumMember", "declaration");
      assertToken(24, 4, "enumMember", "declaration");
    }

    @Test
    void staticField_decl_has_static_modifier() {
      assertToken(27, 30, "property", "declaration", "static");
    }

    @Test
    void staticMethod_decl_has_static_modifier() {
      assertToken(88, 23, "method", "declaration", "static");
    }

    @Test
    void deprecated_method_decl_has_deprecated_modifier() {
      assertToken(106, 16, "method", "declaration", "deprecated");
    }
  }

  @Nested
  class Usages {

    @Test
    void typeParam_in_return_type_is_typeParameter() {
      assertToken(70, 13, "typeParameter");
    }

    @Test
    void typeParam_in_param_type_is_typeParameter() {
      assertToken(70, 30, "typeParameter");
    }

    @Test
    void enumConst_in_field_access_is_enumMember() {
      // Status.ACTIVE
      assertToken(102, 18, "enumMember");
    }

    @Test
    void enumConst_in_annotation_arg_is_enumMember() {
      // RetentionPolicy.RUNTIME and ElementType.METHOD
      assertToken(16, 29, "enumMember");
      assertToken(17, 22, "enumMember");
    }

    @Test
    void staticField_usage_is_property_with_static() {
      // ENGLISH from static import
      assertToken(55, 31, "property", "static");
      // PREFIX in summarize
      assertToken(83, 34, "property", "static");
      // PREFIX in staticHelper
      assertToken(91, 11, "property", "static");
    }

    @Test
    void deprecated_method_usage_has_deprecated_modifier() {
      assertToken(111, 11, "method", "deprecated");
    }
  }

  @Nested
  class NoToken {

    @Test
    void regular_method_decl_has_no_token() {
      // "getName" — instance method, no interesting modifiers
      assertNoToken(37, 16);
    }

    @Test
    void instance_field_usage_has_no_token() {
      // "name" in "return name;"
      assertNoToken(38, 11);
    }

    @Test
    void parameter_has_no_token() {
      // "value" in "public String run(final String value)"
      assertNoToken(49, 33);
    }

    @Test
    void local_variable_has_no_token() {
      // "trimmed" in "var trimmed = input.strip();"
      assertNoToken(89, 8);
    }

    @Test
    void type_reference_has_no_token() {
      // "String" return type of getName
      assertNoToken(37, 9);
    }
  }

  @Nested
  class Encoding {

    int[] encoded;

    @BeforeEach
    void encode() {
      encoded = TokenScanner.encode(tokens);
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
        assertThat(encoded[i]).isBetween(0, TokenScanner.TOKEN_TYPES.size() - 1);
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
