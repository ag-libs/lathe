package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
    tokens = TokenScanner.scan(compiled.trees(), compiled.cu());
  }

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

  @Test
  void regular_method_decl_is_method_with_declaration() {
    // "getName" — instance method declaration (no interesting modifiers)
    assertToken(37, 16, "method", "declaration");
  }

  @Test
  void instance_field_usage_is_property() {
    // "name" in "return name;"
    assertToken(38, 11, "property");
  }

  @Test
  void parameter_decl_is_parameter_with_declaration() {
    // "value" in "public String run(final String value)"
    assertToken(49, 33, "parameter", "declaration");
  }

  @Test
  void local_variable_decl_is_variable_with_declaration() {
    // "trimmed" in "var trimmed = input.strip();"
    assertToken(89, 8, "variable", "declaration");
  }

  @Test
  void class_declaration_has_class_token_with_declaration_modifier() {
    // "Sample" in "public class Sample {"
    assertToken(14, 13, "class", "declaration");
  }

  @Test
  void enum_declaration_has_enum_token_with_declaration_modifier() {
    // "Status" in "enum Status {"
    assertToken(22, 7, "enum", "declaration");
  }

  @Test
  void type_reference_in_code_body_is_class() {
    // "String" return type of getName()
    assertToken(37, 9, "class");
  }

  @Test
  void class_import_has_class_token_on_simple_type_name() {
    // "Objects" in "import java.util.Objects;"
    assertToken(7, 17, "class");
  }

  @Test
  void interface_import_has_interface_token_on_simple_type_name() {
    // "List" in "import java.util.List;"
    assertToken(4, 17, "interface");
  }

  @Test
  void variables_parameters_and_record_components_are_tokenized() throws IOException {
    final var source =
        """
        class T {
          int run(int p) {
            int x = p;
            return x + run(p);
          }
        }
        record R(int a) {}
        """;
    final List<SemanticToken> toks = TokenScannerTestHelper.scanFile(tmp, "T.java", source);

    assertThat(TokenScannerTestHelper.tokenAt(toks, 1, 6).type()).isEqualTo("method"); // run decl
    assertThat(TokenScannerTestHelper.tokenAt(toks, 1, 14).type()).isEqualTo("parameter"); // p decl
    assertThat(TokenScannerTestHelper.tokenAt(toks, 2, 8).type()).isEqualTo("variable"); // x decl
    assertThat(TokenScannerTestHelper.tokenAt(toks, 2, 12).type()).isEqualTo("parameter"); // p use
    assertThat(TokenScannerTestHelper.tokenAt(toks, 3, 11).type()).isEqualTo("variable"); // x use
    assertThat(TokenScannerTestHelper.tokenAt(toks, 3, 15).type()).isEqualTo("method"); // run use
    assertThat(TokenScannerTestHelper.tokenAt(toks, 6, 7).type()).isEqualTo("class"); // record R

    // A record component's synthetic parameter+field share the header offset; only one token emits.
    assertThat(toks.stream().filter(t -> t.line() == 6 && t.character() == 13).toList())
        .singleElement()
        .satisfies(t -> assertThat(t.type()).isEqualTo("property"));
  }

  @Test
  void encode_produces_five_ints_per_token() {
    final int[] encoded = TokenScanner.encode(tokens);
    assertThat(encoded.length).isEqualTo(tokens.size() * 5);
  }

  @Test
  void encode_first_token_deltaLine_equals_absoluteLine() {
    final int[] encoded = TokenScanner.encode(tokens);
    assertThat(encoded.length).isGreaterThanOrEqualTo(5);
    assertThat(encoded[0]).isEqualTo(tokens.getFirst().line());
  }

  @Test
  void encode_tokenTypeIndex_isWithinLegendBounds() {
    final int[] encoded = TokenScanner.encode(tokens);
    for (int i = 3; i < encoded.length; i += 5) {
      assertThat(encoded[i]).isBetween(0, TokenScanner.TOKEN_TYPES.size() - 1);
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

  private SemanticToken tokenAt(final int line, final int character) {
    return TokenScannerTestHelper.tokenAt(tokens, line, character);
  }
}
