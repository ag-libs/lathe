package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ImportTree;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

// The static members a file could import, from the static imports its scope already declares: each
// learned import is resolved through javac against the file's own compile context, so a class only
// another module uses, or a member that is not public and static, is never proposed. Shared by
// completion, the import quick fix and "add missing imports".
public final class StaticImportCandidates {

  private static final String WHOLE_CLASS = "*";

  private StaticImportCandidates() {}

  // A static member worth importing: its owner, its overloads (one candidate per name), and how
  // many files of the scope import it.
  public record Candidate(TypeElement owner, List<Element> overloads, int count) {

    public Candidate {
      ValidCheck.check().notNull(owner, "owner").notEmpty(overloads, "overloads").validate();
      overloads = List.copyOf(overloads);
    }

    public String name() {
      return overloads.get(0).getSimpleName().toString();
    }

    public String qualifiedName() {
      return "%s.%s".formatted(owner.getQualifiedName(), name());
    }

    Candidate plus(final Candidate other) {
      return new Candidate(owner, overloads, count + other.count);
    }
  }

  // The candidates whose name passes `names`, most imported first; none already in scope through
  // the file's own static imports.
  public static List<Candidate> find(
      final LearnedStaticImports learned,
      final AttributedFileAnalysis analysis,
      final Predicate<String> names) {
    if (learned.counts().isEmpty() || analysis.tree() == null) {
      return List.of();
    }

    final Set<String> inFile =
        analysis.tree().getImports().stream()
            .filter(ImportTree::isStatic)
            .map(imp -> imp.getQualifiedIdentifier().toString())
            .collect(Collectors.toUnmodifiableSet());
    return learned.counts().entrySet().stream()
        .flatMap(imp -> resolve(imp.getKey(), imp.getValue(), analysis, names))
        .filter(
            candidate ->
                !inFile.contains(candidate.qualifiedName())
                    && !inFile.contains(wholeClass(candidate.owner())))
        .collect(
            Collectors.toMap(
                Candidate::qualifiedName,
                candidate -> candidate,
                Candidate::plus,
                LinkedHashMap::new))
        .values()
        .stream()
        .sorted(
            Comparator.comparingInt(Candidate::count)
                .reversed()
                .thenComparing(Candidate::qualifiedName))
        .toList();
  }

  private static Stream<Candidate> resolve(
      final String learnedImport,
      final int count,
      final AttributedFileAnalysis analysis,
      final Predicate<String> names) {
    final int dot = learnedImport.lastIndexOf('.');
    final TypeElement owner =
        dot > 0 ? analysis.elements().getTypeElement(learnedImport.substring(0, dot)) : null;
    if (owner == null) {
      return Stream.empty();
    }

    final String member = learnedImport.substring(dot + 1);
    final Map<String, List<Element>> byName =
        analysis.elements().getAllMembers(owner).stream()
            .filter(StaticImportCandidates::importable)
            .filter(el -> member.equals(WHOLE_CLASS) || el.getSimpleName().contentEquals(member))
            .filter(el -> names.test(el.getSimpleName().toString()))
            .collect(
                Collectors.groupingBy(
                    el -> el.getSimpleName().toString(), LinkedHashMap::new, Collectors.toList()));
    return byName.values().stream().map(overloads -> new Candidate(owner, overloads, count));
  }

  private static boolean importable(final Element el) {
    return (el.getKind() == ElementKind.METHOD || el.getKind() == ElementKind.FIELD)
        && el.getModifiers().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC));
  }

  private static String wholeClass(final TypeElement owner) {
    return "%s.%s".formatted(owner.getQualifiedName(), WHOLE_CLASS);
  }
}
