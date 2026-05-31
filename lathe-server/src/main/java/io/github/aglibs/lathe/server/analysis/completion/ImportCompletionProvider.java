package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

final class ImportCompletionProvider {

  private final AttributedFileAnalysis snapshot;

  ImportCompletionProvider(final AttributedFileAnalysis snapshot) {
    this.snapshot = snapshot;
  }

  List<CompletionCandidate> proposeCandidates(final String packageName, final String prefix) {
    if (packageName == null) {
      return topLevelCandidates(prefix);
    }

    return Stream.concat(typeStream(packageName, prefix), subPackageStream(packageName, prefix))
        .toList();
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
        .collect(
            Collectors.toMap(
                el -> el.getSimpleName().toString(),
                el -> CandidateFactory.typeElementCandidate((TypeElement) el),
                (a, b) -> a,
                LinkedHashMap::new))
        .values()
        .stream();
  }

  private Stream<CompletionCandidate> subPackageStream(
      final String packageName, final String prefix) {
    final String pkgPrefix = packageName + ".";
    final int segmentStart = pkgPrefix.length();
    return snapshot.elements().getAllModuleElements().stream()
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
