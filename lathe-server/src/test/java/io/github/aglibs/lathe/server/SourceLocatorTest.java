package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Sample.java line/char positions (LSP 0-based):
//
//  0: import java.lang.annotation.ElementType;
//  ...
// 10: import static java.lang.String.format;
// 11: import static java.util.Locale.ENGLISH;
// 12: (blank)
// 13: /** A sample class ... */
// 14: @SuppressWarnings("unused")
// 15: public class Sample {                      Sample=13
// ...
// 23:   enum Status {
// 24:     ACTIVE, INACTIVE
// ...
// 30:   private final String name;               name=23
// ...
// 38:   public Sample(String name) {             Sample=9  name=23
// 39:     this.name = name;                      name(rhs)=19
// ...
// 48:   public String getName() {                String=9  getName=16
// 49:     return name;                           name=11
// ...
// 67:   public String run(final String value) {  value=33
// 68:     Objects.requireNonNull(value);         requireNonNull=12  value(arg)=27
// 69:     return format("result: %s", value);    format=11  "result:%s"=18     value(arg)=32
// ...
// 73:     return "hello".toUpperCase(ENGLISH);   ENGLISH=31
// ...
// 77:     return items.stream().map(s -> s.toUpperCase()).toList();
//                                        ^30    ^35 ^37
class SourceLocatorTest extends SampleFixture {

  // --- declarations ---

  @Nested
  class Declarations {

    @Test
    void className_resolves_to_class_element() {
      final var element = elementAt(15, 13);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.CLASS);
      assertThat(element.getSimpleName().toString()).isEqualTo("Sample");
    }

    @Test
    void fieldDeclaration_resolves_to_field_element() {
      // "name" in "  private final String name;"
      final var element = elementAt(30, 23);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.FIELD);
      assertThat(element.getSimpleName().toString()).isEqualTo("name");
    }

    @Test
    void methodDeclaration_resolves_to_method_element() {
      // "getName" in "  public String getName() {"
      final var element = elementAt(48, 16);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
      assertThat(element.getSimpleName().toString()).isEqualTo("getName");
    }

    @Test
    void constructorDeclaration_resolves_to_constructor_element() {
      // "Sample" in "  public Sample(String name) {"
      final var element = elementAt(38, 9);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.CONSTRUCTOR);
    }

    @Test
    void returnType_resolves_to_class_element() {
      // "String" in "  public String getName() {"
      final var element = elementAt(48, 9);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.CLASS);
      assertThat(element.getSimpleName().toString()).isEqualTo("String");
    }

    @Test
    void fieldReference_in_method_body_resolves_to_field_element() {
      // "name" in "    return name;"
      final var element = elementAt(49, 11);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.FIELD);
      assertThat(element.getSimpleName().toString()).isEqualTo("name");
    }

    @Test
    void parameterDeclaration_resolves_to_parameter_element() {
      // "name" in "  public Sample(String name) {"
      final var element = elementAt(38, 23);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.PARAMETER);
      assertThat(element.getSimpleName().toString()).isEqualTo("name");
    }

    @Test
    void parameterUsageInBody_resolves_to_parameter_element() {
      // second "name" (rhs) in "    this.name = name;"
      final var element = elementAt(39, 19);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.PARAMETER);
      assertThat(element.getSimpleName().toString()).isEqualTo("name");
    }
  }

  // --- invocations ---

  @Nested
  class Invocations {

    @Test
    void methodName_memberSelect_resolves_to_method() {
      // "requireNonNull" in "Objects.requireNonNull(value)"
      final var element = elementAt(68, 14);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
      assertThat(element.getSimpleName().toString()).isEqualTo("requireNonNull");
    }

    @Test
    void methodName_staticImport_resolves_to_method() {
      // "format" in "format("result: %s", value)"
      final var element = elementAt(69, 13);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
      assertThat(element.getSimpleName().toString()).isEqualTo("format");
    }

    @Test
    void argument_to_requireNonNull_resolves_to_parameter() {
      // "value" in "Objects.requireNonNull(value)"
      final var param = parameterAt(68, 29);
      assertThat(param).isNotNull();
      assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
    }

    @Test
    void firstArgument_to_format_resolves_to_parameter() {
      // "result: %s" string literal in "format("result: %s", value)"
      final var param = parameterAt(69, 22);
      assertThat(param).isNotNull();
      assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
    }

    @Test
    void secondArgument_to_format_resolves_to_parameter() {
      // second "value" in "format("result: %s", value)"
      final var param = parameterAt(69, 34);
      assertThat(param).isNotNull();
      assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
    }

    @Test
    void staticField_argument_resolves_to_field_not_parameter() {
      // "ENGLISH" in "\"hello\".toUpperCase(ENGLISH)" — Locale.ENGLISH is a static field
      final var element = elementAt(73, 34);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.FIELD);
      assertThat(element.getSimpleName().toString()).isEqualTo("ENGLISH");
    }

    @Test
    void staticField_argument_parameterElementAt_returns_null() {
      // parameterElementAt must not mask static fields or enum constants
      final var param = parameterAt(73, 34);
      assertThat(param).isNull();
    }
  }

  // --- imports ---

  @Nested
  class Imports {

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
  }

  // --- overloads and generics ---

  @Nested
  class Overloads {

    @Test
    void overloaded_stringArg_resolves_to_string_param() {
      // "hello" in "overloaded("hello")"
      final var param = parameterAt(93, 16);
      assertThat(param).isNotNull();
      assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
      assertThat(param.asType().toString()).isEqualTo("java.lang.String");
    }

    @Test
    void overloaded_intArg_resolves_to_int_param() {
      // 42 in "overloaded(42)"
      final var param = parameterAt(94, 15);
      assertThat(param).isNotNull();
      assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
      assertThat(param.asType().getKind()).isEqualTo(TypeKind.INT);
    }

    @Test
    void generic_method_param_is_typevar() {
      // "text" in "identity("text")"
      final var param = parameterAt(95, 14);
      assertThat(param).isNotNull();
      assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
      assertThat(param.asType().getKind()).isEqualTo(TypeKind.TYPEVAR);
      assertThat(param.asType().toString()).isEqualTo("T");
    }
  }

  // --- lambdas ---

  @Nested
  class Lambdas {

    @Test
    void lambdaParam_resolves_to_parameter() {
      // "s" (declaration) in "map(s -> s.toUpperCase())"
      final var element = elementAt(77, 30);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.PARAMETER);
      assertThat(element.getSimpleName().toString()).isEqualTo("s");
    }

    @Test
    void lambdaParamUsage_in_body_resolves_to_parameter() {
      // "s" (usage) in "s -> s.toUpperCase()"
      final var element = elementAt(77, 35);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.PARAMETER);
      assertThat(element.getSimpleName().toString()).isEqualTo("s");
    }

    @Test
    void methodCall_in_lambda_body_resolves_to_method() {
      // "toUpperCase" in "s -> s.toUpperCase()"
      final var element = elementAt(77, 37);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);
      assertThat(element.getSimpleName().toString()).isEqualTo("toUpperCase");
    }

    @Test
    void lambda_as_argument_parameterAt_returns_enclosing_method_param() {
      // hovering on lambda param "s" — parameterAt resolves to Stream.map's functional param
      final var param = parameterAt(77, 30);
      assertThat(param).isNotNull();
      assertThat(param.getKind()).isEqualTo(ElementKind.PARAMETER);
    }
  }

  // --- infrastructure ---

  private Element elementAt(final int line, final int character) {
    final var offset = SourceLocator.toOffset(cu, line, character);
    final var path = SourceLocator.pathAt(trees, cu, offset);
    return SourceLocator.elementAt(trees, path);
  }

  private VariableElement parameterAt(final int line, final int character) {
    final var offset = SourceLocator.toOffset(cu, line, character);
    final var path = SourceLocator.pathAt(trees, cu, offset);
    return SourceLocator.parameterElementAt(trees, path);
  }
}
