package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;

final class CompletionItemFactory {

  private static final Logger LOG = Logger.getLogger(CompletionItemFactory.class.getName());

  private final Types types;

  CompletionItemFactory(final Types types) {
    this.types = types;
  }

  static CompletionItem typeIndexEntry(final TypeIndexEntry entry) {
    final var item = new CompletionItem(entry.simpleName());
    item.setInsertText(entry.simpleName());
    item.setFilterText(entry.simpleName());
    item.setDetail(entry.qualifiedName());
    item.setKind(kindFor(entry.kind()));
    return item;
  }

  private static CompletionItemKind kindFor(final TypeKind typeKind) {
    return switch (typeKind) {
      case INTERFACE -> CompletionItemKind.Interface;
      case ENUM -> CompletionItemKind.Enum;
      case RECORD, CLASS, ANNOTATION, UNKNOWN -> CompletionItemKind.Class;
    };
  }

  static CompletionItem typeElement(final TypeElement el) {
    final var simpleName = el.getSimpleName().toString();
    final var item = new CompletionItem(simpleName);
    item.setInsertText(simpleName);
    item.setFilterText(simpleName);
    item.setDetail(el.getQualifiedName().toString());
    item.setKind(kindForElement(el.getKind()));
    return item;
  }

  private static CompletionItemKind kindForElement(final ElementKind kind) {
    return switch (kind) {
      case INTERFACE, ANNOTATION_TYPE -> CompletionItemKind.Interface;
      case ENUM -> CompletionItemKind.Enum;
      default -> CompletionItemKind.Class;
    };
  }

  static CompletionItem keyword(final String kw) {
    final var item = new CompletionItem(kw);
    item.setKind(CompletionItemKind.Keyword);
    item.setInsertText(kw);
    item.setFilterText(kw);
    item.setSortText("8_" + kw);
    return item;
  }

  CompletionItem variable(final String name) {
    final var item = new CompletionItem();
    item.setLabel(name);
    item.setInsertText(name);
    item.setFilterText(name);
    item.setKind(CompletionItemKind.Variable);
    return item;
  }

  CompletionCandidate variableCandidate(final String name, final TypeMirror type) {
    return new CompletionCandidate(
        name, name, CandidateKind.LOCAL_VARIABLE, null, name, false, null, type);
  }

  CompletionItem member(final Element el, final DeclaredType receiverType) {
    return candidateItem(memberCandidate(el, receiverType));
  }

  CompletionCandidate memberCandidate(final Element el, final DeclaredType receiverType) {
    final var name = el.getSimpleName().toString();
    return switch (el.getKind()) {
      case METHOD -> methodCandidate((ExecutableElement) el, receiverType, name);
      case FIELD, ENUM_CONSTANT -> fieldCandidate(el, name);
      default -> throw new IllegalArgumentException("Unsupported completion element: " + el);
    };
  }

  CompletionItem candidateItem(final CompletionCandidate candidate) {
    final var item = new CompletionItem();
    item.setLabel(candidate.label());
    item.setInsertText(candidate.insertText());
    item.setFilterText(candidate.name());
    item.setDetail(candidate.detail());
    item.setSortText(candidate.sortText());
    item.setKind(kindFor(candidate.kind()));
    if (candidate.snippet()) {
      item.setInsertTextFormat(InsertTextFormat.Snippet);
    }

    return item;
  }

  private CompletionCandidate methodCandidate(
      final ExecutableElement method, final DeclaredType receiverType, final String name) {
    final List<? extends TypeMirror> paramTypes = resolveParamTypes(method, receiverType);
    final var params =
        paramTypes.stream().map(this::simpleTypeName).collect(Collectors.joining(", "));
    final boolean snippet = !paramTypes.isEmpty();
    return new CompletionCandidate(
        name,
        "%s(%s)".formatted(name, params),
        CandidateKind.METHOD,
        simpleTypeName(method.getReturnType()),
        snippet ? "%s($1)".formatted(name) : "%s()".formatted(name),
        snippet,
        null,
        method.getReturnType());
  }

  private CompletionCandidate fieldCandidate(final Element field, final String name) {
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.FIELD,
        simpleTypeName(field.asType()),
        name,
        false,
        null,
        field.asType());
  }

  private static CompletionItemKind kindFor(final CandidateKind kind) {
    return switch (kind) {
      case LOCAL_VARIABLE -> CompletionItemKind.Variable;
      case FIELD -> CompletionItemKind.Field;
      case METHOD -> CompletionItemKind.Method;
    };
  }

  private List<? extends TypeMirror> resolveParamTypes(
      final ExecutableElement method, final DeclaredType receiverType) {
    try {
      return ((ExecutableType) types.asMemberOf(receiverType, method)).getParameterTypes();
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[completion-item] asMemberOf failed for %s on %s"
                  .formatted(method.getSimpleName(), receiverType));
      return method.getParameters().stream().map(VariableElement::asType).toList();
    }
  }

  private String simpleTypeName(final TypeMirror type) {
    final var el = types.asElement(type);
    return el != null ? el.getSimpleName().toString() : type.toString();
  }
}
