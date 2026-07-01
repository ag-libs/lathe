package io.github.aglibs.lathe.server.analysis;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public final class DeclarationLocator {

  private DeclarationLocator() {}

  public static Optional<ExecutableElement> findContract(
      final Element element, final Types types, final Elements elements) {
    if (!(element instanceof final ExecutableElement method)) {
      return Optional.empty();
    }

    final Element enclosing = method.getEnclosingElement();
    if (!(enclosing instanceof final TypeElement startType)) {
      return Optional.empty();
    }

    ExecutableElement contract = method;
    final var visited = new HashSet<TypeMirror>();

    final List<? extends TypeMirror> initialSupertypes = types.directSupertypes(startType.asType());
    final var queue = new ArrayDeque<TypeMirror>(initialSupertypes);

    while (!queue.isEmpty()) {
      final TypeMirror currentType = queue.poll();
      if (!visited.add(currentType)) {
        continue;
      }

      if (currentType instanceof final DeclaredType declaredType
          && declaredType.asElement() instanceof final TypeElement typeElement) {

        for (final Element enclosed : typeElement.getEnclosedElements()) {
          if (enclosed instanceof final ExecutableElement candidate) {
            if (matchesContractMethod(
                method, candidate, startType, declaredType, types, elements)) {
              if (contract == method) {
                contract = candidate;
              } else {
                final TypeElement currentContractEnclosing =
                    (TypeElement) contract.getEnclosingElement();
                if (!currentContractEnclosing.getKind().isInterface()
                    && typeElement.getKind().isInterface()) {
                  contract = candidate;
                } else if (currentContractEnclosing.getKind() == typeElement.getKind()) {
                  contract = candidate;
                }
              }

              break;
            }
          }
        }

        final List<? extends TypeMirror> nextSupertypes = types.directSupertypes(currentType);
        queue.addAll(nextSupertypes);
      }
    }

    if (contract == method) {
      return Optional.empty();
    }

    return Optional.of(contract);
  }

  private static boolean matchesContractMethod(
      final ExecutableElement method,
      final ExecutableElement candidate,
      final TypeElement startType,
      final DeclaredType contractOwnerType,
      final Types types,
      final Elements elements) {
    if (elements.overrides(method, candidate, startType)) {
      return true;
    }

    if (method.getModifiers().contains(Modifier.STATIC)
        || candidate.getModifiers().contains(Modifier.STATIC)
        || candidate.getModifiers().contains(Modifier.PRIVATE)) {
      return false;
    }

    if (!method.getSimpleName().contentEquals(candidate.getSimpleName())) {
      return false;
    }

    if (!(method.asType() instanceof final ExecutableType methodType)) {
      return false;
    }

    final TypeMirror candidateMemberType = types.asMemberOf(contractOwnerType, candidate);
    if (!(candidateMemberType instanceof final ExecutableType candidateType)) {
      return false;
    }

    return types.isSubsignature(methodType, candidateType);
  }
}
