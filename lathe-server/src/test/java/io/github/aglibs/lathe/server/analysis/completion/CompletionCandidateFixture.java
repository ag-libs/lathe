package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.TempSourceCompiler;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

final class CompletionCandidateFixture implements AutoCloseable {

  private static final String TEST_URI = "file:///Fixture.java";

  private final TempSourceCompiler compiler;
  private final AttributedFileAnalysis analysis;
  private final CompletionItemFactory factory;

  CompletionCandidateFixture(final String source) {
    this.compiler = new TempSourceCompiler();
    this.analysis = compiler.compile(TEST_URI, source, CompileMode.FULL).fileAnalysis();
    this.factory = new CompletionItemFactory(analysis.types());
  }

  CompletionCandidate method(final String name) {
    return memberCandidate(fixtureType(), methodNamed(name));
  }

  CompletionCandidate field(final String name) {
    return memberCandidate(fixtureType(), elementNamed(ElementKind.FIELD, name));
  }

  CompletionCandidate noArgMethod(final String qualifiedType, final String name) {
    return memberCandidate(typeElement(qualifiedType), methodNamed(name).and(noArgs()));
  }

  TypeMirror type(final String qualifiedName) {
    return typeElement(qualifiedName).asType();
  }

  SemanticCompletionContext expected(final TypeMirror type) {
    return new SemanticCompletionContext(analysis, new ExpectedValue.Type(type), false);
  }

  SemanticCompletionContext unknown() {
    return new SemanticCompletionContext(analysis, new ExpectedValue.Unknown(), false);
  }

  SemanticCompletionContext valueContext() {
    return new SemanticCompletionContext(analysis, new ExpectedValue.Unknown(), true);
  }

  SemanticCompletionContext noSlot() {
    return new SemanticCompletionContext(analysis, new ExpectedValue.NoSlot(), false);
  }

  @Override
  public void close() {
    compiler.close();
  }

  private TypeElement fixtureType() {
    return typeElement("Fixture");
  }

  private TypeElement typeElement(final String qualifiedName) {
    final TypeElement type = analysis.elements().getTypeElement(qualifiedName);
    if (type == null) {
      throw new AssertionError("No type " + qualifiedName);
    }

    return type;
  }

  private CompletionCandidate memberCandidate(
      final TypeElement type, final Predicate<Element> predicate) {
    final DeclaredType declaredType = (DeclaredType) type.asType();
    return analysis.elements().getAllMembers(type).stream()
        .filter(predicate)
        .findFirst()
        .map(element -> factory.memberCandidate(element, declaredType))
        .orElseThrow(() -> new AssertionError("No matching member on " + type));
  }

  private static Predicate<Element> methodNamed(final String name) {
    return elementNamed(ElementKind.METHOD, name);
  }

  private static Predicate<Element> elementNamed(final ElementKind kind, final String name) {
    return element -> element.getKind() == kind && name.equals(element.getSimpleName().toString());
  }

  private static Predicate<Element> noArgs() {
    return element -> ((ExecutableElement) element).getParameters().isEmpty();
  }
}
