package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.VariableNameSuggester;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;

// At a real name slot (explicit type before the caret) suggest identifier names from that type,
// instead of the empty list the slot used to return. Names come from VariableNameSuggester,
// filtered by the typed prefix and de-conflicted with names already visible in scope.
final class DeclarationNameCompletionProvider {

  private DeclarationNameCompletionProvider() {}

  static List<CompletionCandidate> propose(
      final ParsedSentinel parsed, final AttributedFileAnalysis analysis, final int cursorOffset) {
    final String typeSimpleName = usableName(parsed.declaredTypeText());
    if (typeSimpleName == null) {
      return List.of();
    }

    final boolean field = parsed.enclosingMethod() == null;
    final Set<String> taken = visibleNames(analysis, cursorOffset);
    final List<String> names =
        VariableNameSuggester.suggest(typeSimpleName, parsed.declaredElementTypeName(), null, taken)
            .stream()
            .filter(name -> matchesPrefix(name, parsed.prefix()))
            .toList();
    return IntStream.range(0, names.size())
        .mapToObj(i -> candidate(names.get(i), i, field))
        .toList();
  }

  // declaredTypeText is the type's AST simple name. Use it only when it is a plain identifier, so
  // primitives (int) and arrays (String[]) are dropped for a later slice.
  private static String usableName(final String declaredTypeText) {
    return declaredTypeText != null && SourceVersion.isIdentifier(declaredTypeText)
        ? declaredTypeText
        : null;
  }

  private static boolean matchesPrefix(final String name, final String prefix) {
    return prefix.isEmpty() || name.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  private static CompletionCandidate candidate(
      final String name, final int rank, final boolean field) {
    return new CompletionCandidate(
        name,
        name,
        field ? CandidateKind.FIELD : CandidateKind.LOCAL_VARIABLE,
        field ? "suggested field name" : "suggested local name",
        name,
        false,
        "0_%02d_%s".formatted(rank, name),
        null,
        null,
        null);
  }

  private static Set<String> visibleNames(
      final AttributedFileAnalysis analysis, final int cursorOffset) {
    if (analysis == null) {
      return Set.of();
    }

    return Stream.iterate(
            TypeResolver.resolveScope(analysis, cursorOffset),
            Objects::nonNull,
            Scope::getEnclosingScope)
        .flatMap(DeclarationNameCompletionProvider::variableNames)
        .collect(Collectors.toSet());
  }

  private static Stream<String> variableNames(final Scope scope) {
    return StreamSupport.stream(scope.getLocalElements().spliterator(), false)
        .filter(DeclarationNameCompletionProvider::isVariable)
        .map(element -> element.getSimpleName().toString());
  }

  private static boolean isVariable(final Element element) {
    return switch (element.getKind()) {
      case LOCAL_VARIABLE, PARAMETER, FIELD, EXCEPTION_PARAMETER, RESOURCE_VARIABLE -> true;
      default -> false;
    };
  }
}
