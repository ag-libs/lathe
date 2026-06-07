package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

final class CandidateGenerator {

  private static final Logger LOG = Logger.getLogger(CandidateGenerator.class.getName());

  private static final List<String> OBJECT_METHOD_NAMES =
      Arrays.stream(Object.class.getDeclaredMethods()).map(Method::getName).distinct().toList();

  private final AttributedFileAnalysis snapshot;
  private final Types types;
  private final CandidateFactory itemFactory;

  CandidateGenerator(final AttributedFileAnalysis snapshot) {
    this.snapshot = snapshot;
    this.types = snapshot.types();
    this.itemFactory = new CandidateFactory(types);
  }

  List<CompletionCandidate> proposeMemberAccessCandidates(
      final TypeMirror receiverType,
      final String prefix,
      final boolean isStaticAccess,
      final Scope scope) {
    if (!(receiverType instanceof final DeclaredType declaredType)) {
      return List.of();
    }

    final var element = types.asElement(declaredType);
    if (!(element instanceof final TypeElement typeEl)) {
      return List.of();
    }

    return snapshot.elements().getAllMembers(typeEl).stream()
        .filter(
            el ->
                el.getKind() == ElementKind.METHOD
                    || el.getKind() == ElementKind.FIELD
                    || el.getKind() == ElementKind.ENUM_CONSTANT)
        .filter(el -> el.getModifiers().contains(Modifier.STATIC) == isStaticAccess)
        .filter(el -> isAccessible(el, declaredType, scope))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(
            el -> {
              final var candidate = itemFactory.memberCandidate(el, declaredType);
              return candidate.withSortText(sortKey(el));
            })
        .toList();
  }

  List<CompletionCandidate> proposeNestedTypes(final TypeElement outer, final String prefix) {
    return outer.getEnclosedElements().stream()
        .filter(
            el ->
                el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.RECORD)
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(el -> CandidateFactory.typeElementCandidate((TypeElement) el))
        .toList();
  }

  List<CompletionCandidate> proposeEnumConstantCandidates(
      final TypeElement enumType, final String prefix) {
    final String typeName = enumType.getSimpleName().toString();
    return enumType.getEnclosedElements().stream()
        .filter(el -> el.getKind() == ElementKind.ENUM_CONSTANT)
        .filter(el -> (typeName + "." + el.getSimpleName()).startsWith(prefix))
        .map(el -> enumConstantCandidate(enumType, el, typeName + "." + el.getSimpleName()))
        .toList();
  }

  List<CompletionCandidate> proposeUnqualifiedEnumConstantCandidates(
      final TypeElement enumType, final String prefix) {
    return enumType.getEnclosedElements().stream()
        .filter(el -> el.getKind() == ElementKind.ENUM_CONSTANT)
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(el -> enumConstantCandidate(enumType, el, el.getSimpleName().toString()))
        .toList();
  }

  private static CompletionCandidate enumConstantCandidate(
      final TypeElement enumType, final Element constant, final String label) {
    return new CompletionCandidate(
        label,
        label,
        CandidateKind.FIELD,
        enumType.getSimpleName().toString(),
        label,
        false,
        null,
        enumType.asType(),
        enumType.getQualifiedName().toString(),
        null);
  }

  List<CompletionCandidate> proposeSimpleNameCandidates(
      final String enclosingClass,
      final String enclosingMethod,
      final String prefix,
      final int cursorOffset,
      final SemanticCompletionContext semanticContext) {
    final var context =
        new SimpleNameContext(
            enclosingClass, enclosingMethod, prefix, cursorOffset, semanticContext);
    return new SimpleNameProvider(snapshot, itemFactory, context).collect();
  }

  private static String sortKey(final Element el) {
    if (el.getKind() == ElementKind.METHOD
        && OBJECT_METHOD_NAMES.contains(el.getSimpleName().toString())) {
      return "9_" + el.getSimpleName();
    }

    return "0_" + el.getSimpleName();
  }

  private boolean isAccessible(
      final Element el, final DeclaredType receiverType, final Scope scope) {
    if (scope == null) {
      return true;
    }

    try {
      return snapshot.trees().isAccessible(scope, el, receiverType);
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[proposal] isAccessible failed for %s on %s"
                  .formatted(el.getSimpleName(), receiverType));
      return true;
    }
  }
}
