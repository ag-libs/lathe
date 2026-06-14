package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ImportTree;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

public final class ImportAnalyzer {

  private final AttributedFileAnalysis analysis;

  public ImportAnalyzer(final AttributedFileAnalysis analysis) {
    this.analysis = analysis;
  }

  public Range insertionRange() {
    if (analysis == null || analysis.tree() == null) {
      return null;
    }

    final var cu = analysis.tree();
    final var positions = analysis.trees().getSourcePositions();
    final var lineMap = cu.getLineMap();

    final var imports = cu.getImports();
    if (!imports.isEmpty()) {
      final long endOffset = positions.getEndPosition(cu, imports.getLast());
      if (endOffset >= 0) {
        final int insertLine = (int) lineMap.getLineNumber(endOffset);
        return new Range(new Position(insertLine, 0), new Position(insertLine, 0));
      }
    }

    final var pkg = cu.getPackage();
    if (pkg != null) {
      final long endOffset = positions.getEndPosition(cu, pkg);
      if (endOffset >= 0) {
        final int insertLine = (int) lineMap.getLineNumber(endOffset);
        return new Range(new Position(insertLine, 0), new Position(insertLine, 0));
      }
    }

    return new Range(new Position(0, 0), new Position(0, 0));
  }

  public Set<String> importedQualifiedNames() {
    if (analysis == null || analysis.tree() == null) {
      return Set.of();
    }

    return analysis.tree().getImports().stream()
        .filter(imp -> !imp.isStatic())
        .map(imp -> imp.getQualifiedIdentifier().toString())
        .collect(Collectors.toUnmodifiableSet());
  }

  public boolean needsImport(final String qualifiedName) {
    final int lastDot = qualifiedName.lastIndexOf('.');
    if (lastDot < 0) {
      return false;
    }

    final String pkg = qualifiedName.substring(0, lastDot);
    if ("java.lang".equals(pkg)) {
      return false;
    }

    if (analysis != null
        && analysis.tree() != null
        && analysis.tree().getPackageName() != null
        && pkg.equals(analysis.tree().getPackageName().toString())) {
      return false;
    }

    return !importedQualifiedNames().contains(qualifiedName);
  }

  public TextEdit importEdit(final String qualifiedName) {
    if (!needsImport(qualifiedName)) {
      return null;
    }

    final var insertionRange = insertionRange();
    if (insertionRange == null) {
      return null;
    }

    return new TextEdit(insertionRange, "import %s;\n".formatted(qualifiedName));
  }

  public Set<String> importedStaticNames() {
    if (analysis == null || analysis.tree() == null) {
      return Set.of();
    }

    return analysis.tree().getImports().stream()
        .filter(ImportTree::isStatic)
        .map(imp -> imp.getQualifiedIdentifier().toString())
        .collect(Collectors.toUnmodifiableSet());
  }
}
