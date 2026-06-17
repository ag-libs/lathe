package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.Test;

// Sample.java line/char positions (LSP 0-based):
//
//  0: import java.lang.annotation.ElementType;
//  ...
// 11: import static java.lang.String.format;
// 12: import static java.util.Locale.ENGLISH;
// 13: (blank)
// 14: public class Sample {                      Sample=13
// 15: (blank)
// 16:   @Retention(RetentionPolicy.RUNTIME)
// 17:   @Target(ElementType.METHOD)
// 18:   public @interface Logged {
// 19:     String value() default "";
// 20:   }
// 21: (blank)
// 22:   enum Status {
// 23:     ACTIVE, INACTIVE
// 24:   }
// 25: (blank)
// 26:   private static final String PREFIX = "item-";
// 27: (blank)
// 28:   private final String name;               name=23
// 29:   private int count;
// 30: (blank)
// 31:   public Sample(String name) {             Sample=9  name=23
// 32:     this.name = name;                      name(rhs)=19
// ...
// 36:   public String getName() {                String=9  getName=16
// 37:     return name;                           name=11
// ...
// 48:   public String run(final String value) {  value=33
// 49:     Objects.requireNonNull(value);         requireNonNull=12  value(arg)=27
// 50:     return format("result: %s", value);    format=11  "result:%s"=18     value(arg)=32
// ...
// 54:     return "hello".toUpperCase(ENGLISH);   ENGLISH=31
// ...
// 58:     return items.stream().map(s -> s.toUpperCase()).toList();
//                                        ^30    ^35 ^37
class SourceLocatorTest extends SampleFixture {

  // --- declarations ---

  @Test
  void className_resolves_to_class_element() {
    final var element = elementAt(14, 13);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.CLASS);
    assertThat(element.getSimpleName().toString()).isEqualTo("Sample");
  }

  @Test
  void fieldDeclaration_resolves_to_field_element() {
    // "name" in "  private final String name;"
    final var element = elementAt(29, 23);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.FIELD);
    assertThat(element.getSimpleName().toString()).isEqualTo("name");
  }

  @Test
  void methodDeclaration_resolves_to_method_element() {
    // "getName" in "  public String getName() {"
    final var element = elementAt(37, 16);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
    assertThat(element.getSimpleName().toString()).isEqualTo("getName");
  }

  @Test
  void declarationPath_resolves_correct_overload() {
    // String overload of "overloaded" (0-based line 62)
    final var stringElement = elementAt(62, 16);
    final var stringPath = SourceLocator.declarationPath(compiled.cu(), stringElement);
    assertThat(stringPath).isNotNull();
    assertThat(
            compiled
                .trees()
                .getSourcePositions()
                .getStartPosition(compiled.cu(), stringPath.getLeaf()))
        .isEqualTo(
            compiled
                .trees()
                .getSourcePositions()
                .getStartPosition(compiled.cu(), pathAt(62, 16).getLeaf()));

    // int overload of "overloaded" (0-based line 66)
    final var intElement = elementAt(66, 13);
    final var intPath = SourceLocator.declarationPath(compiled.cu(), intElement);
    assertThat(intPath).isNotNull();
    assertThat(
            compiled
                .trees()
                .getSourcePositions()
                .getStartPosition(compiled.cu(), intPath.getLeaf()))
        .isEqualTo(
            compiled
                .trees()
                .getSourcePositions()
                .getStartPosition(compiled.cu(), pathAt(66, 13).getLeaf()));
  }

  @Test
  void constructorDeclaration_resolves_to_constructor_element() {
    // "Sample" in "  public Sample(String name) {"
    final var element = elementAt(32, 9);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.CONSTRUCTOR);
  }

  @Test
  void returnType_resolves_to_class_element() {
    // "String" in "  public String getName() {"
    final var element = elementAt(37, 9);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.CLASS);
    assertThat(element.getSimpleName().toString()).isEqualTo("String");
  }

  @Test
  void fieldReference_in_method_body_resolves_to_field_element() {
    // "name" in "    return name;"
    final var element = elementAt(38, 11);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.FIELD);
    assertThat(element.getSimpleName().toString()).isEqualTo("name");
  }

  @Test
  void parameterDeclaration_resolves_to_parameter_element() {
    // "name" in "  public Sample(String name) {"
    final var element = elementAt(32, 23);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.PARAMETER);
    assertThat(element.getSimpleName().toString()).isEqualTo("name");
  }

  @Test
  void parameterUsageInBody_resolves_to_parameter_element() {
    // second "name" (rhs) in "    this.name = name;"
    final var element = elementAt(33, 19);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.PARAMETER);
    assertThat(element.getSimpleName().toString()).isEqualTo("name");
  }

  // --- invocations ---

  @Test
  void methodName_memberSelect_resolves_to_method() {
    // "requireNonNull" in "Objects.requireNonNull(value)"
    final var element = elementAt(50, 14);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
    assertThat(element.getSimpleName().toString()).isEqualTo("requireNonNull");
  }

  @Test
  void methodName_staticImport_resolves_to_method() {
    // "format" in "format("result: %s", value)"
    final var element = elementAt(51, 13);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
    assertThat(element.getSimpleName().toString()).isEqualTo("format");
  }

  @Test
  void argument_to_requireNonNull_resolves_to_parameter() {
    // "value" in "Objects.requireNonNull(value)"
    final var param = parameterAt(50, 29);
    assertThat(param).isNotNull();
    assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
  }

  @Test
  void firstArgument_to_format_resolves_to_parameter() {
    // "result: %s" string literal in "format("result: %s", value)"
    final var param = parameterAt(51, 22);
    assertThat(param).isNotNull();
    assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
  }

  @Test
  void secondArgument_to_format_resolves_to_parameter() {
    // second "value" in "format("result: %s", value)"
    final var param = parameterAt(51, 34);
    assertThat(param).isNotNull();
    assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
  }

  @Test
  void staticField_argument_resolves_to_field_not_parameter() {
    // "ENGLISH" in "\"hello\".toUpperCase(ENGLISH)" — Locale.ENGLISH is a static field
    final var element = elementAt(55, 34);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.FIELD);
    assertThat(element.getSimpleName().toString()).isEqualTo("ENGLISH");
  }

  @Test
  void staticField_argument_parameterElementAt_returns_null() {
    // parameterElementAt must not mask static fields or enum constants
    final var param = parameterAt(55, 34);
    assertThat(param).isNull();
  }

  // --- imports ---

  @Test
  void staticMethodImport_resolves_to_method() {
    // "format" in "import static java.lang.String.format;"
    final var element = elementAt(10, 36);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
    assertThat(element.getSimpleName().toString()).isEqualTo("format");
  }

  @Test
  void staticFieldImport_resolves_to_field() {
    // "ENGLISH" in "import static java.util.Locale.ENGLISH;"
    final var element = elementAt(11, 36);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.FIELD);
    assertThat(element.getSimpleName().toString()).isEqualTo("ENGLISH");
  }

  // --- overloads and generics ---

  @Test
  void overloaded_stringArg_resolves_to_string_param() {
    // "hello" in "overloaded("hello")"
    final var param = parameterAt(75, 16);
    assertThat(param).isNotNull();
    assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
    assertThat(param.asType().toString()).isEqualTo("java.lang.String");
  }

  @Test
  void overloaded_intArg_resolves_to_int_param() {
    // 42 in "overloaded(42)"
    final var param = parameterAt(76, 15);
    assertThat(param).isNotNull();
    assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
    assertThat(param.asType().getKind()).isEqualTo(TypeKind.INT);
  }

  @Test
  void generic_method_param_is_typevar() {
    // "text" in "identity("text")"
    final var param = parameterAt(77, 14);
    assertThat(param).isNotNull();
    assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
    assertThat(param.asType().getKind()).isEqualTo(TypeKind.TYPEVAR);
    assertThat(param.asType().toString()).isEqualTo("T");
  }

  // --- lambdas ---

  @Test
  void lambdaParam_resolves_to_parameter() {
    // "s" (declaration) in "map(s -> s.toUpperCase())"
    final var element = elementAt(59, 30);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.PARAMETER);
    assertThat(element.getSimpleName().toString()).isEqualTo("s");
  }

  @Test
  void lambdaParamUsage_in_body_resolves_to_parameter() {
    // "s" (usage) in "s -> s.toUpperCase()"
    final var element = elementAt(59, 35);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.PARAMETER);
    assertThat(element.getSimpleName().toString()).isEqualTo("s");
  }

  @Test
  void methodCall_in_lambda_body_resolves_to_method() {
    // "toUpperCase" in "s -> s.toUpperCase()"
    final var element = elementAt(59, 37);
    assertThat(element).isNotNull();
    assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
    assertThat(element.getSimpleName().toString()).isEqualTo("toUpperCase");
  }

  @Test
  void lambda_as_argument_parameterAt_returns_enclosing_method_param() {
    // hovering on lambda param "s" — parameterAt resolves to Stream.map's functional param
    final var param = parameterAt(59, 30);
    assertThat(param).isNotNull();
    assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
  }

  // --- identifier lookup ---

  @Test
  void findLastIdentifierBetween_repeatedIdentifier_returnsLastBoundedMatch() {
    final String source = "class Foo { Foo Foo; }";

    final long result =
        SourceLocator.findLastIdentifierBetween(
            source, source.indexOf("{"), source.indexOf(";"), "Foo");

    assertThat(result).isEqualTo(source.lastIndexOf("Foo"));
  }

  @Test
  void findLastIdentifierBetween_partialIdentifier_returnsNoMatch() {
    final String source = "class Test { int Foobar; }";

    final long result =
        SourceLocator.findLastIdentifierBetween(
            source, source.indexOf("{"), source.indexOf(";"), "Foo");

    assertThat(result).isEqualTo(-1L);
  }
}
