package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

final class ImportCompletionProvider {

  private final AttributedFileAnalysis snapshot;
  private final Scope scope;

  ImportCompletionProvider(final AttributedFileAnalysis snapshot, final Scope scope) {
    this.snapshot = snapshot;
    this.scope = scope;
  }

  List<CompletionCandidate> proposeCandidates(final String packageName, final String prefix) {
    if (packageName == null) {
      return topLevelCandidates(prefix);
    }

    final var typeStream = typeStream(packageName, prefix);
    final var subPackageStream = subPackageStream(packageName, prefix);
    return Stream.concat(typeStream, subPackageStream).toList();
  }

  private List<CompletionCandidate> topLevelCandidates(final String prefix) {
    final var packages =
        snapshot.elements().getAllModuleElements().stream()
            .flatMap(m -> m.getEnclosedElements().stream())
            .filter(el -> el.getKind() == ElementKind.PACKAGE)
            .map(el -> ((PackageElement) el).getQualifiedName().toString())
            .filter(name -> !name.isEmpty())
            .map(ImportCompletionProvider::firstSegment)
            .filter(seg -> seg.startsWith(prefix))
            .distinct()
            .map(ImportCompletionProvider::packageCandidate);

    final Stream<CompletionCandidate> staticKeyword =
        "static".startsWith(prefix)
            ? Stream.of(
                new CompletionCandidate(
                    "static",
                    "static",
                    CandidateKind.KEYWORD,
                    null,
                    "static",
                    false,
                    null,
                    null,
                    null,
                    null))
            : Stream.empty();

    return Stream.concat(staticKeyword, packages).toList();
  }

  private Stream<CompletionCandidate> typeStream(final String packageName, final String prefix) {
    return snapshot.elements().getAllPackageElements(packageName).stream()
        .flatMap(pkg -> pkg.getEnclosedElements().stream())
        .filter(el -> isImportableType(el.getKind()))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .filter(el -> scope == null || snapshot.trees().isAccessible(scope, (TypeElement) el))
        .collect(
            Collectors.toMap(
                el -> el.getSimpleName().toString(),
                el -> CandidateFactory.typeElementCandidate((TypeElement) el),
                (a, b) -> a,
                LinkedHashMap::new))
        .values()
        .stream();
  }

  private boolean isModuleAccessible(final ModuleElement m) {
    if (scope == null) {
      return true;
    }
    for (final var el : m.getEnclosedElements()) {
      if (el.getKind() != ElementKind.PACKAGE) {
        continue;
      }

      for (final var member : el.getEnclosedElements()) {
        if (isImportableType(member.getKind())) {
          return snapshot.trees().isAccessible(scope, (TypeElement) member);
        }
      }
    }

    return false;
  }

  private Stream<CompletionCandidate> subPackageStream(
      final String packageName, final String prefix) {
    final String pkgPrefix = packageName + ".";
    final int segmentStart = pkgPrefix.length();
    return snapshot.elements().getAllModuleElements().stream()
        .filter(this::isModuleAccessible)
        .flatMap(m -> m.getEnclosedElements().stream())
        .filter(el -> el.getKind() == ElementKind.PACKAGE)
        .map(el -> ((PackageElement) el).getQualifiedName().toString())
        .filter(name -> name.startsWith(pkgPrefix))
        .map(name -> name.substring(segmentStart))
        // Modules declare only leaf packages (e.g. "io.helidon.service.codegen"), not every
        // intermediate level. Take only the first segment so "io." yields "helidon", not empty.
        .map(
            seg -> {
              final int dot = seg.indexOf('.');
              return dot < 0 ? seg : seg.substring(0, dot);
            })
        .filter(seg -> seg.startsWith(prefix))
        .distinct()
        .map(ImportCompletionProvider::packageCandidate);
  }

  private static boolean isImportableType(final ElementKind kind) {
    return kind == ElementKind.CLASS
        || kind == ElementKind.INTERFACE
        || kind == ElementKind.ENUM
        || kind == ElementKind.RECORD
        || kind == ElementKind.ANNOTATION_TYPE;
  }

  private static String firstSegment(final String qualifiedName) {
    final int dot = qualifiedName.indexOf('.');
    return dot < 0 ? qualifiedName : qualifiedName.substring(0, dot);
  }

  private static CompletionCandidate packageCandidate(final String name) {
    return new CompletionCandidate(
        name, name, CandidateKind.PACKAGE, null, name, false, null, null, null, null);
  }
}
