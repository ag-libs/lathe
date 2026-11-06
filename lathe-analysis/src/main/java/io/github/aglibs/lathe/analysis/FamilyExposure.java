package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

// A file that never names a member's family (its declaring type and subtypes) can still reach a
// static method, a field or an enum constant through an expression of a family type (an enum
// constant as a switch label over one), and that expression's type comes from a non-private
// declaration naming the family. Only a non-family type's declaration counts: reaching a family
// type's own member takes naming that type, or a family value already. So among the files naming
// the family, these declarations are the ones a hidden reference needs: a non-family type's
// non-private member, supertype or type parameter handing out a family value, and a class extending
// a family type the type index does not know yet, whose own subclasses the search would not see.
// Members come from javac's model of each type, so Lombok's and a record's generated ones count.
public final class FamilyExposure {

  private final Elements elements;
  private final Types types;
  private final Set<String> family;

  private FamilyExposure(final Elements elements, final Types types, final Set<String> family) {
    this.elements = elements;
    this.types = types;
    this.family = family;
  }

  // The file's exposing declarations, each as Type.member (or Type for a type-level one); empty
  // when none, or when there is no family to check.
  public static List<String> exposures(
      final AttributedFileAnalysis analysis, final Set<String> family) {
    if (family.isEmpty() || analysis == null || analysis.tree() == null) {
      return List.of();
    }

    final var exposure = new FamilyExposure(analysis.elements(), analysis.types(), family);
    final var found = new ArrayList<String>();
    for (final Tree declaration : analysis.tree().getTypeDecls()) {
      final Element element =
          analysis.trees().getElement(TreePath.getPath(analysis.tree(), declaration));
      if (element instanceof final TypeElement type) {
        exposure.collect(type, found);
      }
    }

    return List.copyOf(found);
  }

  // A private type, and everything nested in it, is visible only inside this file.
  private void collect(final TypeElement type, final List<String> found) {
    if (type.getModifiers().contains(Modifier.PRIVATE)) {
      return;
    }

    final String name = elements.getBinaryName(type).toString();
    if (!family.contains(name)) {
      found.addAll(typeExposures(type, name));
      found.addAll(memberExposures(type, name));
    }

    for (final TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) {
      collect(nested, found);
    }
  }

  private List<String> typeExposures(final TypeElement type, final String name) {
    if (extendsFamily(type)) {
      return List.of("%s (a subtype not yet indexed)".formatted(name));
    }

    final boolean exposed =
        types.directSupertypes(type.asType()).stream()
                .anyMatch(supertype -> mentionsFamily(supertype, new HashSet<>()))
            || type.getTypeParameters().stream()
                .anyMatch(parameter -> mentionsFamily(parameter.asType(), new HashSet<>()));
    return exposed ? List.of(name) : List.of();
  }

  private List<String> memberExposures(final TypeElement type, final String name) {
    final boolean lambdaTarget = type.getKind().isInterface();
    return type.getEnclosedElements().stream()
        .filter(member -> !(member instanceof TypeElement))
        .filter(member -> !member.getModifiers().contains(Modifier.PRIVATE))
        .filter(member -> exposes(member, lambdaTarget))
        .map(member -> "%s.%s".formatted(name, member.getSimpleName()))
        .distinct()
        .toList();
  }

  // A field's or a return's value is the caller's to use, and a type variable's bound types what it
  // gets back. A parameter only takes the caller's value, so it gives the family away nested (a
  // lambda the caller writes for Consumer<Family> gets a Family), or whole to a lambda implementing
  // an interface's method.
  private boolean exposes(final Element member, final boolean lambdaTarget) {
    if (!(member instanceof final ExecutableElement method)) {
      return mentionsFamily(member.asType(), new HashSet<>());
    }

    final var seenVariables = new HashSet<Element>();
    return mentionsFamily(method.getReturnType(), seenVariables)
        || anyMentions(
            method.getTypeParameters().stream().map(Element::asType).toList(), seenVariables)
        || method.getParameters().stream()
            .map(Element::asType)
            .anyMatch(
                parameter ->
                    lambdaTarget
                        ? mentionsFamily(parameter, new HashSet<>())
                        : mentionsBelowTop(parameter));
  }

  // Whether a family type appears inside the type, past its outermost type and array dimensions.
  private boolean mentionsBelowTop(final TypeMirror type) {
    if (type.getKind() == TypeKind.ARRAY) {
      return mentionsBelowTop(((ArrayType) type).getComponentType());
    }

    if (!(type instanceof final DeclaredType declared)) {
      return false;
    }

    final var seenVariables = new HashSet<Element>();
    return anyMentions(declared.getTypeArguments(), seenVariables)
        || mentionsFamily(declared.getEnclosingType(), seenVariables);
  }

  // A non-family type with a family supertype is a subtype the type index has not seen. Past this
  // check no supertype is in the family, so mentionsFamily finds only their type arguments.
  private boolean extendsFamily(final TypeElement type) {
    final var pending = new ArrayDeque<TypeMirror>(types.directSupertypes(type.asType()));
    final var seen = new HashSet<String>();
    while (!pending.isEmpty()) {
      final TypeMirror supertype = pending.removeFirst();
      if (!(types.asElement(supertype) instanceof final TypeElement element)) {
        continue;
      }

      final String name = elements.getBinaryName(element).toString();
      if (family.contains(name)) {
        return true;
      }

      if (seen.add(name)) {
        pending.addAll(types.directSupertypes(supertype));
      }
    }

    return false;
  }

  // Whether a family type appears anywhere in the type: as itself, a type argument, an array's
  // element, or a type variable's or wildcard's bound. Type variables are followed once, as their
  // bounds may refer to themselves.
  private boolean mentionsFamily(final TypeMirror type, final Set<Element> seenVariables) {
    return switch (type.getKind()) {
      case DECLARED -> mentionsFamily((DeclaredType) type, seenVariables);
      case ARRAY -> mentionsFamily(((ArrayType) type).getComponentType(), seenVariables);
      case TYPEVAR -> {
        final var variable = (TypeVariable) type;
        yield seenVariables.add(variable.asElement())
            && (mentionsFamily(variable.getUpperBound(), seenVariables)
                || mentionsFamily(variable.getLowerBound(), seenVariables));
      }
      case WILDCARD -> {
        final var wildcard = (WildcardType) type;
        yield (wildcard.getExtendsBound() != null
                && mentionsFamily(wildcard.getExtendsBound(), seenVariables))
            || (wildcard.getSuperBound() != null
                && mentionsFamily(wildcard.getSuperBound(), seenVariables));
      }
      case INTERSECTION -> anyMentions(((IntersectionType) type).getBounds(), seenVariables);
      case UNION -> anyMentions(((UnionType) type).getAlternatives(), seenVariables);
      default -> false;
    };
  }

  private boolean mentionsFamily(final DeclaredType type, final Set<Element> seenVariables) {
    final var element = (TypeElement) type.asElement();
    return family.contains(elements.getBinaryName(element).toString())
        || anyMentions(type.getTypeArguments(), seenVariables)
        || mentionsFamily(type.getEnclosingType(), seenVariables);
  }

  private boolean anyMentions(
      final List<? extends TypeMirror> types, final Set<Element> seenVariables) {
    for (final TypeMirror type : types) {
      if (mentionsFamily(type, seenVariables)) {
        return true;
      }
    }

    return false;
  }
}
