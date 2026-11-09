package io.github.aglibs.lathe.analysis;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

final class HoverFormatter {

  private HoverFormatter() {}

  public static Optional<String> format(
      final Element element,
      final TypeMirror type,
      final String javadoc,
      final String origin,
      final TypeDisplayFormatter fmt,
      final List<String> sourceParamNames) {
    if (element == null && type == null) {
      return Optional.empty();
    }

    final String sig;
    if (element instanceof final ExecutableElement exe) {
      final var params = exe.getParameters();
      final int last = params.size() - 1;
      final boolean varargs = exe.isVarArgs();
      final String paramStr =
          IntStream.range(0, params.size())
              .mapToObj(
                  i -> formatParam(params.get(i), fmt, sourceParamNames, i, varargs && i == last))
              .collect(Collectors.joining(", "));
      final String typeParams = formatTypeParameters(exe.getTypeParameters());
      final String typePrefix = typeParams.isEmpty() ? "" : "%s ".formatted(typeParams);
      if (exe.getKind() == ElementKind.CONSTRUCTOR) {
        // A constructor's element name is <init> and its return type is void; render it as the
        // enclosing type's name with no return type, matching how the source declares it, under
        // its class's line for context. An anonymous class has no name for that line.
        final var owner = (TypeElement) exe.getEnclosingElement();
        final String constructor =
            "%s%s(%s)".formatted(typePrefix, owner.getSimpleName(), paramStr);
        sig =
            owner.getSimpleName().isEmpty()
                ? constructor
                : "%s\n%s".formatted(typeLine(owner), constructor);
      } else {
        final String returnType =
            fmt != null ? fmt.format(exe.getReturnType()) : exe.getReturnType().toString();
        sig = "%s%s %s(%s)".formatted(typePrefix, returnType, exe.getSimpleName(), paramStr);
      }
    } else if (element instanceof final TypeElement te) {
      sig = typeLine(te);
    } else if (element != null && type != null) {
      sig = "%s %s".formatted(type, element.getSimpleName());
    } else if (type != null) {
      sig = type.toString();
    } else {
      sig = element.toString();
    }

    var result = "```java\n%s\n```".formatted(sig);
    if (javadoc != null && !javadoc.isBlank()) {
      result = "%s\n\n%s".formatted(result, javadoc);
    }
    if (origin != null) {
      result = "%s\n\n*source: %s*".formatted(result, origin);
    }
    return Optional.of(result);
  }

  private static String typeLine(final TypeElement te) {
    final var kind =
        switch (te.getKind()) {
          case ANNOTATION_TYPE -> "@interface";
          case RECORD -> "record";
          case ENUM -> "enum";
          case INTERFACE -> "interface";
          default -> "class";
        };
    return "%s %s%s"
        .formatted(kind, te.getSimpleName(), formatTypeParameters(te.getTypeParameters()));
  }

  // What javadoc's own pages show beside the text: that the element is deprecated, unless its
  // javadoc already opens with the deprecation, and the method it overrides or, failing that, the
  // interface method it implements. Empty when none applies.
  static Optional<String> notes(
      final Element element,
      final boolean deprecationShown,
      final Elements elements,
      final Types types) {
    if (element == null) {
      return Optional.empty();
    }

    final String notes =
        Stream.of(
                deprecationShown ? Optional.<String>empty() : deprecation(element, elements),
                overridden(element, elements, types))
            .flatMap(Optional::stream)
            .collect(Collectors.joining("  \n"));
    return notes.isEmpty() ? Optional.empty() : Optional.of(notes);
  }

  // A deprecated class's constructor is deprecated too, though javac marks only the class.
  private static Optional<String> deprecation(final Element element, final Elements elements) {
    final boolean deprecated =
        elements.isDeprecated(element)
            || (element.getKind() == ElementKind.CONSTRUCTOR
                && elements.isDeprecated(element.getEnclosingElement()));
    return deprecated ? Optional.of(JavadocMarkdownPrinter.DEPRECATED) : Optional.empty();
  }

  // As javadoc reads it: a method found up the superclass chain is overridden, the nearest first;
  // otherwise one from an interface is specified.
  private static Optional<String> overridden(
      final Element element, final Elements elements, final Types types) {
    if (!(element instanceof final ExecutableElement method)
        || method.getKind() != ElementKind.METHOD
        || !(method.getEnclosingElement() instanceof final TypeElement owner)) {
      return Optional.empty();
    }

    TypeMirror superclass = owner.getSuperclass();
    while (types.asElement(superclass) instanceof final TypeElement type) {
      final Optional<ExecutableElement> parent =
          ElementFilter.methodsIn(type.getEnclosedElements()).stream()
              .filter(candidate -> elements.overrides(method, candidate, owner))
              .findFirst();
      if (parent.isPresent()) {
        return parent.map(found -> note("Overrides", found));
      }

      superclass = type.getSuperclass();
    }

    return ReferenceTarget.overriddenMethods(method, owner, types, elements).stream()
        .filter(candidate -> candidate.getEnclosingElement().getKind().isInterface())
        .findFirst()
        .map(found -> note("Specified by", found));
  }

  private static String note(final String label, final ExecutableElement method) {
    return "**%s** `%s.%s`"
        .formatted(label, method.getEnclosingElement().getSimpleName(), method.getSimpleName());
  }

  static String formatParam(
      final VariableElement param,
      final TypeDisplayFormatter fmt,
      final List<String> sourceNames,
      final int index,
      final boolean vararg) {
    final String rawType = fmt != null ? fmt.format(param.asType()) : param.asType().toString();
    // A varargs parameter's declared type is an array; render it as T... rather than T[].
    final String typeName =
        vararg && rawType.endsWith("[]")
            ? "%s...".formatted(rawType.substring(0, rawType.length() - 2))
            : rawType;
    final String name =
        (sourceNames != null && index < sourceNames.size())
            ? sourceNames.get(index)
            : param.getSimpleName().toString();
    return SourceParser.isSyntheticName(name) ? typeName : "%s %s".formatted(typeName, name);
  }

  // A type-parameter declaration, e.g. "<T>" or "<K, V>", or "" when there is none. Names only
  // (bounds omitted) to keep the hover line compact.
  private static String formatTypeParameters(
      final List<? extends TypeParameterElement> typeParams) {
    return typeParams.isEmpty()
        ? ""
        : typeParams.stream()
            .map(tp -> tp.getSimpleName().toString())
            .collect(Collectors.joining(", ", "<", ">"));
  }
}
