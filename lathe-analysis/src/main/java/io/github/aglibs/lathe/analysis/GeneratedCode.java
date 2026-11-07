package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;

// What javac or Lombok generated: generated declarations, the source a generated member comes
// from, and the accessors Lombok generates for a field.
public final class GeneratedCode {

  private static final String LOMBOK_GENERATED = "lombok.Generated";

  private GeneratedCode() {}

  // Lombok marks each member it generates with @lombok.Generated, unless lombok.config turns that
  // off. Such a member takes the span of the code it comes from (a field's name, an annotation), so
  // what maps source positions to trees skips it.
  public static boolean isGenerated(final Tree tree) {
    final ModifiersTree modifiers = TreePaths.modifiersOf(tree);
    return modifiers != null
        && modifiers.getAnnotations().stream()
            .anyMatch(
                annotation -> annotation.getAnnotationType().toString().equals(LOMBOK_GENERATED));
  }

  // The same mark on an element, which a class file keeps too (lombok.Generated has class
  // retention).
  public static boolean isGenerated(final Element element) {
    return element.getAnnotationMirrors().stream()
        .anyMatch(annotation -> annotation.getAnnotationType().toString().equals(LOMBOK_GENERATED));
  }

  // Where a member Lombok generates comes from, as written: the field an accessor reads or writes,
  // else the first class around it that Lombok did not generate. Accessors are matched by Lombok's
  // default names, then by the names left once lombok.config strips a prefix from the field; other
  // naming from lombok.config lands on the class.
  public static Element generatedSource(final Element generated) {
    Element owner = generated.getEnclosingElement();
    while (isGenerated(owner)) {
      owner = owner.getEnclosingElement();
    }

    final String name = generated.getSimpleName().toString();
    final List<Element> fields =
        owner.getEnclosedElements().stream()
            .filter(member -> member.getKind() == ElementKind.FIELD && !isGenerated(member))
            .map(Element.class::cast)
            .toList();
    return Stream.<Function<String, Set<String>>>of(
            GeneratedCode::accessorNames, GeneratedCode::prefixedAccessorNames)
        .flatMap(
            names ->
                fields.stream()
                    .filter(field -> names.apply(field.getSimpleName().toString()).contains(name)))
        .findFirst()
        .orElse(owner);
  }

  // The members Lombok generated from a field: its getter, setter and wither. None for anything
  // else.
  public static List<Element> generatedAccessors(final Element field) {
    if (field.getKind() != ElementKind.FIELD) {
      return List.of();
    }

    return field.getEnclosingElement().getEnclosedElements().stream()
        .filter(member -> member.getKind() == ElementKind.METHOD && isGenerated(member))
        .filter(method -> generatedSource(method).equals(field))
        .map(Element.class::cast)
        .toList();
  }

  // Lombok's default accessor names for a field: get/is/set/with and the capitalized name, or the
  // field's own name (fluent); a boolean isActive gets isActive() and setActive().
  private static Set<String> accessorNames(final String field) {
    final String property =
        field.length() > 2 && field.startsWith("is") && Character.isUpperCase(field.charAt(2))
            ? field.substring(2)
            : Character.toUpperCase(field.charAt(0)) + field.substring(1);
    return Stream.of(field, "get" + property, "is" + property, "set" + property, "with" + property)
        .collect(Collectors.toUnmodifiableSet());
  }

  // The accessor names Lombok gives a field once lombok.accessors.prefix strips a prefix from it: a
  // lowercase prefix before an uppercase letter (fCount names Count), or one ending in an
  // underscore (m_count names count).
  private static Set<String> prefixedAccessorNames(final String field) {
    return IntStream.range(1, field.length())
        .filter(
            at ->
                field.charAt(at - 1) == '_'
                    || Character.isUpperCase(field.charAt(at))
                        && field.substring(0, at).chars().allMatch(Character::isLowerCase))
        .mapToObj(at -> Character.toLowerCase(field.charAt(at)) + field.substring(at + 1))
        .flatMap(property -> accessorNames(property).stream())
        .collect(Collectors.toUnmodifiableSet());
  }
}
