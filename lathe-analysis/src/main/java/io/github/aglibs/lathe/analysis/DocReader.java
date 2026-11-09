package io.github.aglibs.lathe.analysis;

import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.MethodDoc;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;

// The method docs one request reads, each parsed at most once however many overloads or override
// chains ask for it. It lives for one hover or signature-help request on that module's worker
// thread and is never stored, so its caches are plain collections.
final class DocReader {

  private final JavadocLocator locator;
  private final AttributedFileAnalysis analysis;
  private final List<Path> sourceRoots;
  private final Map<ExecutableElement, MethodDoc> documented = new HashMap<>();
  private final Set<ExecutableElement> undocumented = new HashSet<>();

  DocReader(
      final JavadocLocator locator,
      final AttributedFileAnalysis analysis,
      final List<Path> sourceRoots) {
    this.locator = locator;
    this.analysis = analysis;
    this.sourceRoots = List.copyOf(sourceRoots);
  }

  AttributedFileAnalysis analysis() {
    return analysis;
  }

  List<Path> sourceRoots() {
    return sourceRoots;
  }

  Optional<MethodDoc> doc(final ExecutableElement method) {
    if (documented.containsKey(method) || undocumented.contains(method)) {
      return Optional.ofNullable(documented.get(method));
    }

    final Optional<MethodDoc> doc = locator.methodDoc(method, analysis.trees(), sourceRoots);
    doc.ifPresentOrElse(found -> documented.put(method, found), () -> undocumented.add(method));
    return doc;
  }
}
