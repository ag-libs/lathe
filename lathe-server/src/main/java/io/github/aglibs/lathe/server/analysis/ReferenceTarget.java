package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public record ReferenceTarget(
    ElementKind kind,
    String qualifiedName,
    String simpleName,
    String erasedDescriptor,
    SearchScope scope,
    List<String> overriddenDeclarers,
    boolean overrideFamilyBounded) {

  public enum SearchScope {
    DECLARING_FILE,
    DECLARING_MODULE,
    REACTOR_MODULES
  }

  public ReferenceTarget {
    ValidCheck.check()
        .notNull(kind, "kind")
        .notBlank(qualifiedName, "qualifiedName")
        .notBlank(simpleName, "simpleName")
        .notNull(scope, "scope")
        .notNull(overriddenDeclarers, "overriddenDeclarers")
        .validate();
    overriddenDeclarers = List.copyOf(overriddenDeclarers);
  }

  static ReferenceTarget from(final Element element, final Types types, final Elements elements) {
    final var accessor = recordAccessorFor(element);
    if (accessor != null) {
      return from(accessor, types, elements);
    }

    final var kind = element.getKind();
    final var simpleName = element.getSimpleName().toString();
    final var scope = scopeFor(element);

    return switch (kind) {
      case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> {
        final var te = (TypeElement) element;
        yield new ReferenceTarget(
            kind, te.getQualifiedName().toString(), simpleName, null, scope, List.of(), false);
      }
      case METHOD, CONSTRUCTOR -> {
        final var ee = (ExecutableElement) element;
        final var owner = (TypeElement) ee.getEnclosingElement();
        final boolean method = kind == ElementKind.METHOD;
        final List<String> declarers =
            method ? overriddenDeclarers(ee, owner, types, elements) : List.of();
        yield new ReferenceTarget(
            kind,
            elements.getBinaryName(owner).toString(),
            simpleName,
            buildDescriptor(ee, types),
            scope,
            declarers,
            method);
      }
      case FIELD, ENUM_CONSTANT -> {
        final var owner = (TypeElement) element.getEnclosingElement();
        yield new ReferenceTarget(
            kind,
            elements.getBinaryName(owner).toString(),
            simpleName,
            null,
            scope,
            List.of(),
            false);
      }
      default ->
          new ReferenceTarget(
              kind,
              enclosingBinaryName(element, elements),
              simpleName,
              null,
              scope,
              List.of(),
              false);
    };
  }

  /**
   * A record component (resolved either as a {@code RECORD_COMPONENT} element or, as javac usually
   * reports the header, its backing {@code FIELD}) is normalised to its generated accessor. The
   * accessor is the public, reactor-visible symbol that call sites resolve to, giving broad
   * candidate discovery and the correct search scope; backing-field reads inside the record body
   * are matched separately in {@link #matches}.
   */
  private static ExecutableElement recordAccessorFor(final Element element) {
    final var kind = element.getKind();
    if (kind == ElementKind.RECORD_COMPONENT) {
      return ((RecordComponentElement) element).getAccessor();
    }

    if (kind != ElementKind.FIELD) {
      return null;
    }

    final var owner = element.getEnclosingElement();
    if (owner.getKind() != ElementKind.RECORD) {
      return null;
    }

    final var component = componentNamed((TypeElement) owner, element.getSimpleName());
    return component == null ? null : component.getAccessor();
  }

  private static RecordComponentElement componentNamed(
      final TypeElement record, final CharSequence name) {
    return record.getRecordComponents().stream()
        .filter(component -> component.getSimpleName().contentEquals(name))
        .findFirst()
        .orElse(null);
  }

  private static SearchScope scopeFor(final Element element) {
    final var kind = element.getKind();
    if (kind == ElementKind.LOCAL_VARIABLE
        || kind == ElementKind.PARAMETER
        || kind == ElementKind.EXCEPTION_PARAMETER
        || kind == ElementKind.RESOURCE_VARIABLE) {
      return SearchScope.DECLARING_FILE;
    }

    final var mods = element.getModifiers();
    if (mods.contains(Modifier.PRIVATE)) {
      return SearchScope.DECLARING_FILE;
    }

    if (mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED)) {
      return SearchScope.REACTOR_MODULES;
    }

    return SearchScope.DECLARING_MODULE;
  }

  ExecutableElement resolveMethodElement(final Elements elements, final Types types) {
    if (kind != ElementKind.METHOD) {
      return null;
    }

    final var owner = elements.getTypeElement(qualifiedName.replace('$', '.'));
    if (owner == null) {
      return null;
    }

    return owner.getEnclosedElements().stream()
        .filter(ExecutableElement.class::isInstance)
        .map(ExecutableElement.class::cast)
        .filter(el -> matches(el, types, elements))
        .findFirst()
        .orElse(null);
  }

  boolean matchesWithOverrides(
      final Element element,
      final Types types,
      final Elements elements,
      final ExecutableElement targetMethod) {
    if (matches(element, types, elements)) {
      return true;
    }

    if (kind != ElementKind.METHOD) {
      return false;
    }

    if (!(element instanceof final ExecutableElement ee)) {
      return false;
    }

    if (targetMethod == null) {
      return false;
    }

    final var eeOwner = (TypeElement) ee.getEnclosingElement();
    final var targetOwner = (TypeElement) targetMethod.getEnclosingElement();
    return elements.overrides(ee, targetMethod, eeOwner)
        || elements.overrides(targetMethod, ee, targetOwner);
  }

  boolean matches(final Element element, final Types types, final Elements elements) {
    if (element == null) {
      return false;
    }

    if (kind == ElementKind.METHOD && matchesRecordComponentMember(element, types, elements)) {
      return true;
    }

    if (element.getKind() != kind) {
      return false;
    }

    if (!simpleName.equals(element.getSimpleName().toString())) {
      return false;
    }

    return switch (kind) {
      case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> {
        final var te = (TypeElement) element;
        yield qualifiedName.equals(te.getQualifiedName().toString());
      }

      case METHOD, CONSTRUCTOR -> {
        final var ee = (ExecutableElement) element;
        final var owner = (TypeElement) ee.getEnclosingElement();
        yield qualifiedName.equals(elements.getBinaryName(owner).toString())
            && erasedDescriptor.equals(buildDescriptor(ee, types));
      }

      case FIELD, ENUM_CONSTANT -> {
        final var owner = (TypeElement) element.getEnclosingElement();
        yield qualifiedName.equals(elements.getBinaryName(owner).toString());
      }
      default -> qualifiedName.equals(enclosingBinaryName(element, elements));
    };
  }

  /**
   * When this target is a record accessor, other members backing the same component count as
   * references too: the backing {@code FIELD} (in-body reads and writes) and the canonical
   * constructor {@code PARAMETER} that javac reports for compact/canonical constructor bodies. A
   * same-named parameter of a non-canonical constructor is a distinct member and is excluded.
   */
  private boolean matchesRecordComponentMember(
      final Element element, final Types types, final Elements elements) {
    final var elementKind = element.getKind();
    if (elementKind != ElementKind.FIELD && elementKind != ElementKind.PARAMETER) {
      return false;
    }

    if (!simpleName.equals(element.getSimpleName().toString())) {
      return false;
    }

    final var record = enclosingRecord(element);
    if (record == null
        || !qualifiedName.equals(elements.getBinaryName(record).toString())
        || componentNamed(record, simpleName) == null) {
      return false;
    }

    return elementKind == ElementKind.FIELD
        || isCanonicalConstructorParameter(element, record, types);
  }

  private static TypeElement enclosingRecord(final Element element) {
    var owner = element.getEnclosingElement();
    if (owner.getKind() == ElementKind.CONSTRUCTOR) {
      owner = owner.getEnclosingElement();
    }

    return owner.getKind() == ElementKind.RECORD ? (TypeElement) owner : null;
  }

  private static boolean isCanonicalConstructorParameter(
      final Element parameter, final TypeElement record, final Types types) {
    final var constructor = (ExecutableElement) parameter.getEnclosingElement();
    final List<? extends VariableElement> parameters = constructor.getParameters();
    final List<? extends RecordComponentElement> components = record.getRecordComponents();
    return parameters.size() == components.size()
        && IntStream.range(0, parameters.size())
            .allMatch(
                i -> types.isSameType(parameters.get(i).asType(), components.get(i).asType()));
  }

  /**
   * Binary names of the supertypes (transitively) that declare a method this target method
   * overrides. The reference-candidate planner uses these, together with the declaring type and its
   * subtypes, to bound a method search to its override family instead of every file that spells the
   * simple name. Empty when the method overrides nothing (e.g. a record accessor with no
   * interface).
   */
  private static List<String> overriddenDeclarers(
      final ExecutableElement method,
      final TypeElement owner,
      final Types types,
      final Elements elements) {
    final var declarers = new LinkedHashSet<String>();
    final var visited = new HashSet<String>();
    final var pending = new ArrayDeque<TypeMirror>(types.directSupertypes(owner.asType()));
    while (!pending.isEmpty()) {
      final var supertype = pending.removeFirst();
      if (!(types.asElement(supertype) instanceof final TypeElement ste)) {
        continue;
      }

      final var binaryName = elements.getBinaryName(ste).toString();
      if (!visited.add(binaryName)) {
        continue;
      }

      if (declaresOverridden(method, owner, ste, elements)) {
        declarers.add(binaryName);
      }

      pending.addAll(types.directSupertypes(supertype));
    }

    return List.copyOf(declarers);
  }

  private static boolean declaresOverridden(
      final ExecutableElement method,
      final TypeElement owner,
      final TypeElement supertype,
      final Elements elements) {
    return ElementFilter.methodsIn(supertype.getEnclosedElements()).stream()
        .anyMatch(candidate -> elements.overrides(method, candidate, owner));
  }

  private static String buildDescriptor(final ExecutableElement method, final Types types) {
    return method.getParameters().stream()
        .map(p -> types.erasure(p.asType()).toString())
        .collect(Collectors.joining(",", "(", ")"));
  }

  private static String enclosingBinaryName(final Element element, final Elements elements) {
    Element e = element.getEnclosingElement();
    while (e != null && !(e instanceof TypeElement)) {
      e = e.getEnclosingElement();
    }

    if (e instanceof final TypeElement te) {
      return elements.getBinaryName(te).toString();
    }

    return element.getSimpleName().toString();
  }
}
