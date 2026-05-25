package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class TypeIndexValidator {

  private static final Logger LOG = Logger.getLogger(TypeIndexValidator.class.getName());
  private static final Set<String> WARNED_SPLIT_PACKAGES = ConcurrentHashMap.newKeySet();

  private final FileAnalysis analysis;
  private final Map<String, Boolean> visiblePackages = new HashMap<>();

  TypeIndexValidator(final FileAnalysis analysis) {
    this.analysis = analysis;
  }

  boolean isResolvable(final TypeIndexEntry entry) {
    if (analysis == null) {
      return true;
    }

    return visiblePackages.computeIfAbsent(entry.packageName(), this::isVisiblePackage)
        && analysis.elements().getTypeElement(entry.qualifiedName()) != null;
  }

  private boolean isVisiblePackage(final String packageName) {
    final var packages = analysis.elements().getAllPackageElements(packageName);
    if (packages.size() > 1 && WARNED_SPLIT_PACKAGES.add(packageName)) {
      LOG.warning(
          () ->
              "[type-index] split package %s has %d visible owners; skipping candidates"
                  .formatted(packageName, packages.size()));
    }

    return packages.size() == 1;
  }
}
