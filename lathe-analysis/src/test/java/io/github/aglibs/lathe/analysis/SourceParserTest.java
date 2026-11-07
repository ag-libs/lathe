package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import org.junit.jupiter.api.Test;

class SourceParserTest {

  private static final String SOURCE =
      """
      import java.util.List;
      class Outer {
        static class A {
          class Inner { void m() {} }
        }
        static class B {
          class Inner { void m() {} }
        }
        void f(List<String> names) {}
        void f(String[] names) {}
        void f(int count) {}
      }
      """;

  // A nested type and its members are found under their own enclosing types, not under another
  // type of the same simple name.
  @Test
  void declarationPath_sameNamedNestedTypes_findsTheTargetsOwn() {
    final AttributedFileAnalysis analysis = compile();
    final TypeElement aInner = analysis.elements().getTypeElement("Outer.A.Inner");
    final TypeElement bInner = analysis.elements().getTypeElement("Outer.B.Inner");

    assertDeclares(analysis, aInner);
    assertDeclares(analysis, bInner);
    assertDeclares(analysis, method(aInner, 0));
    assertDeclares(analysis, method(bInner, 0));
  }

  // An overload is found by its parameter types as written: a generic type by its raw name, an
  // array, a primitive.
  @Test
  void declarationPath_overloadsByParameterType_findsEach() {
    final AttributedFileAnalysis analysis = compile();
    final TypeElement outer = analysis.elements().getTypeElement("Outer");
    final List<ExecutableElement> overloads = ElementFilter.methodsIn(outer.getEnclosedElements());

    assertThat(overloads).hasSize(3);
    overloads.forEach(overload -> assertDeclares(analysis, overload));
  }

  @Test
  void declarationPath_noTarget_none() {
    assertThat(SourceParser.declarationPath(compile().tree(), null)).isNull();
  }

  private static void assertDeclares(final AttributedFileAnalysis analysis, final Element target) {
    assertThat(SourceParser.declarationPath(analysis.tree(), target).getLeaf())
        .as("the declaration of %s", target)
        .isSameAs(analysis.trees().getPath(target).getLeaf());
  }

  private static ExecutableElement method(final TypeElement type, final int index) {
    return ElementFilter.methodsIn(type.getEnclosedElements()).get(index);
  }

  private static AttributedFileAnalysis compile() {
    return new TempSourceCompiler()
        .compile(TempSourceCompiler.TEST_URI, SOURCE, CompileMode.OPEN)
        .fileAnalysis();
  }
}
