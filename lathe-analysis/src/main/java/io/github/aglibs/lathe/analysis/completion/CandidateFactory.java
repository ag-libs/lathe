package io.github.aglibs.lathe.analysis.completion;

import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.ImportAnalyzer;
import io.github.aglibs.lathe.analysis.SourceParser;
import io.github.aglibs.lathe.analysis.TypeDisplayFormatter;
import io.github.aglibs.lathe.analysis.internal.SourceTypeNames;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

final class CandidateFactory {

  private static final Logger LOG = Logger.getLogger(CandidateFactory.class.getName());

  private final Types types;
  private final TypeDisplayFormatter typeDisplayFormatter;

  CandidateFactory(final Types types) {
    this.types = types;
    this.typeDisplayFormatter = new TypeDisplayFormatter(types);
  }

  // A type from the index, written as code at the cursor names it (see SourceTypeNames): a nested
  // type from a type the code can name (Map.Entry), whose import it adds, never imported itself.
  // Without an analysis yet (no `names`), or for a reactor type with no class file yet, there is no
  // element to look at: the type is written from its top-level type.
  static CompletionCandidate typeIndexCandidate(
      final TypeIndexEntry entry,
      final AttributedFileAnalysis analysis,
      final SourceTypeNames names) {
    final TypeElement type =
        names == null ? null : analysis.elements().getTypeElement(entry.qualifiedName());
    if (type != null) {
      return typeCandidate(
          entry.simpleName(),
          names.sourceName(type),
          entry.qualifiedName(),
          kindFor(entry.kind()),
          null,
          importEditFor(names, type));
    }

    final int nested = entry.binaryName().indexOf('$');
    final String topLevel =
        nested < 0 ? entry.binaryName() : entry.binaryName().substring(0, nested);
    return typeCandidate(
        entry.simpleName(),
        entry.className().replace('$', '.'),
        entry.qualifiedName(),
        kindFor(entry.kind()),
        null,
        new ImportAnalyzer(analysis).needsImport(topLevel)
            ? new ImportEdit(topLevel, false)
            : null);
  }

  // The import the code naming `type` needs, if the file has no line for it yet.
  static ImportEdit importEditFor(final SourceTypeNames names, final TypeElement type) {
    return names.importFor(type).map(name -> new ImportEdit(name, false)).orElse(null);
  }

  private static CandidateKind kindFor(final TypeKind typeKind) {
    return switch (typeKind) {
      case INTERFACE -> CandidateKind.TYPE_INTERFACE;
      case ENUM -> CandidateKind.TYPE_ENUM;
      case RECORD, CLASS, ANNOTATION, UNKNOWN -> CandidateKind.TYPE_CLASS;
    };
  }

  static CompletionCandidate typeElementCandidate(final TypeElement el) {
    final var simpleName = el.getSimpleName().toString();
    return typeCandidate(
        simpleName,
        simpleName,
        el.getQualifiedName().toString(),
        kindForElement(el.getKind()),
        el.asType(),
        null);
  }

  // A type written as code at the cursor names it, with the import that name needs.
  static CompletionCandidate typeElementCandidate(
      final TypeElement el, final SourceTypeNames names) {
    return typeCandidate(
        el.getSimpleName().toString(),
        names.sourceName(el),
        el.getQualifiedName().toString(),
        kindForElement(el.getKind()),
        el.asType(),
        importEditFor(names, el));
  }

  private static CandidateKind kindForElement(final ElementKind kind) {
    return switch (kind) {
      case INTERFACE, ANNOTATION_TYPE -> CandidateKind.TYPE_INTERFACE;
      case ENUM -> CandidateKind.TYPE_ENUM;
      default -> CandidateKind.TYPE_CLASS;
    };
  }

  private static CompletionCandidate typeCandidate(
      final String simpleName,
      final String insertText,
      final String qualifiedName,
      final CandidateKind kind,
      final TypeMirror valueType,
      final ImportEdit importEdit) {
    final String packageName = QualifiedNames.packageName(qualifiedName).orElse(null);
    return new CompletionCandidate(
        simpleName,
        simpleName,
        kind,
        qualifiedName,
        insertText,
        false,
        null,
        null,
        packageName,
        valueType,
        qualifiedName,
        importEdit);
  }

  // An array's clone() has no backing element (it is public with covariant return T[], unlike the
  // protected Object.clone()), so synthesize the method candidate directly.
  CompletionCandidate arrayCloneCandidate(final ArrayType arrayType) {
    final String arrayDisplay = typeDisplayFormatter.format(arrayType);
    return new CompletionCandidate(
        "clone",
        "clone",
        CandidateKind.METHOD,
        "%s.clone() : %s".formatted(arrayDisplay, arrayDisplay),
        "clone()",
        false,
        null,
        "()",
        arrayDisplay,
        arrayType,
        arrayDisplay,
        null);
  }

  CompletionCandidate variableCandidate(final String name, final TypeMirror type) {
    final String formattedType = type != null ? typeDisplayFormatter.format(type) : null;
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.LOCAL_VARIABLE,
        null,
        name,
        false,
        null,
        null,
        formattedType,
        type,
        null,
        null);
  }

  CompletionCandidate memberCandidate(final Element el, final DeclaredType receiverType) {
    final var name = el.getSimpleName().toString();
    return switch (el.getKind()) {
      case METHOD -> methodCandidate((ExecutableElement) el, receiverType, name);
      case FIELD, ENUM_CONSTANT -> fieldCandidate(el, name);
      case CLASS, INTERFACE, ENUM, RECORD -> typeElementCandidate((TypeElement) el);
      default ->
          throw new IllegalArgumentException("Unsupported completion element: %s".formatted(el));
    };
  }

  private CompletionCandidate methodCandidate(
      final ExecutableElement method, final DeclaredType receiverType, final String name) {
    final ExecutableType executableType = resolveExecutableType(method, receiverType);
    final List<? extends TypeMirror> paramTypes = executableType.getParameterTypes();
    final String paramsWithNames = paramsWithNames(method, paramTypes);
    final boolean snippet = !paramTypes.isEmpty();
    final String returnType = typeDisplayFormatter.format(executableType.getReturnType());
    final String declaringType = declaringTypeSimpleName(method);
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.METHOD,
        "%s.%s(%s) : %s".formatted(declaringType, name, paramsWithNames, returnType),
        snippet ? "%s($1)".formatted(name) : "%s()".formatted(name),
        snippet,
        null,
        "(%s)".formatted(paramsWithNames),
        returnType,
        executableType.getReturnType(),
        declaringType(method),
        null);
  }

  private CompletionCandidate fieldCandidate(final Element field, final String name) {
    final String type = typeDisplayFormatter.format(field.asType());
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.FIELD,
        type,
        name,
        false,
        null,
        null,
        type,
        field.asType(),
        declaringType(field),
        null);
  }

  static String declaringType(final Element element) {
    return element.getEnclosingElement() instanceof final TypeElement typeElement
        ? typeElement.getQualifiedName().toString()
        : null;
  }

  private static String declaringTypeSimpleName(final Element element) {
    return element.getEnclosingElement() instanceof final TypeElement typeElement
        ? typeElement.getSimpleName().toString()
        : "";
  }

  private String paramsWithNames(
      final ExecutableElement method, final List<? extends TypeMirror> paramTypes) {
    final var parameters = method.getParameters();
    return IntStream.range(0, paramTypes.size())
        .mapToObj(
            index -> {
              final String paramName =
                  index < parameters.size() ? parameters.get(index).getSimpleName().toString() : "";
              final TypeMirror type = paramTypes.get(index);
              final String typeText = typeDisplayFormatter.format(type);
              return displayParameter(typeText, paramName);
            })
        .collect(Collectors.joining(", "));
  }

  private static String displayParameter(final String typeText, final String name) {
    if (name.isBlank() || SourceParser.isSyntheticName(name)) {
      return typeText;
    }

    return "%s %s".formatted(typeText, name);
  }

  private ExecutableType resolveExecutableType(
      final ExecutableElement method, final DeclaredType receiverType) {
    try {
      return (ExecutableType) types.asMemberOf(receiverType, method);
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[completion-item] asMemberOf failed for %s on %s"
                  .formatted(method.getSimpleName(), receiverType));
      return (ExecutableType) method.asType();
    }
  }
}
