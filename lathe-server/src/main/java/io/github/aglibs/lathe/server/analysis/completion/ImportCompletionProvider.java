package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

final class ImportCompletionProvider {

  private final FileAnalysis snapshot;

  ImportCompletionProvider(final FileAnalysis snapshot) {
    this.snapshot = snapshot;
  }

  List<CompletionItem> propose(final String packageName, final String prefix) {
    return Stream.concat(typeStream(packageName, prefix), subPackageStream(packageName, prefix))
        .toList();
  }

  private Stream<CompletionItem> typeStream(final String packageName, final String prefix) {
    return snapshot.elements().getAllPackageElements(packageName).stream()
        .flatMap(pkg -> pkg.getEnclosedElements().stream())
        .filter(el -> isImportableType(el.getKind()))
        .map(el -> el.getSimpleName().toString())
        .filter(name -> name.startsWith(prefix))
        .distinct()
        .map(ImportCompletionProvider::typeItem);
  }

  private Stream<CompletionItem> subPackageStream(final String packageName, final String prefix) {
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
        .map(ImportCompletionProvider::packageItem);
  }

  private static boolean isImportableType(final ElementKind kind) {
    return kind == ElementKind.CLASS
        || kind == ElementKind.INTERFACE
        || kind == ElementKind.ENUM
        || kind == ElementKind.RECORD
        || kind == ElementKind.ANNOTATION_TYPE;
  }

  private static CompletionItem typeItem(final String name) {
    final var item = new CompletionItem();
    item.setLabel(name);
    item.setKind(CompletionItemKind.Class);
    return item;
  }

  private static CompletionItem packageItem(final String name) {
    final var item = new CompletionItem();
    item.setLabel(name);
    item.setKind(CompletionItemKind.Module);
    return item;
  }
}
