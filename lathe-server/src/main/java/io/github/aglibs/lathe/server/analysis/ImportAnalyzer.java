package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.SourcePositions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;

public final class ImportAnalyzer {

  private final AttributedFileAnalysis analysis;

  public ImportAnalyzer(final AttributedFileAnalysis analysis) {
    this.analysis = analysis;
  }

  // Empty without a parsed file.
  public Optional<ImportLayout> layout() {
    if (analysis == null || analysis.tree() == null) {
      return Optional.empty();
    }

    final CompilationUnitTree cu = analysis.tree();
    final SourcePositions positions = analysis.trees().getSourcePositions();
    final LineMap lineMap = cu.getLineMap();
    final List<ImportLayout.ImportLine> lines =
        cu.getImports().stream()
            // An import still being typed may have no end position yet.
            .filter(imp -> positions.getEndPosition(cu, imp) >= 0)
            .map(
                imp ->
                    new ImportLayout.ImportLine(
                        spelling(imp),
                        (int) lineMap.getLineNumber(positions.getStartPosition(cu, imp)) - 1,
                        (int) lineMap.getLineNumber(positions.getEndPosition(cu, imp)) - 1))
            .toList();
    final long packageEnd =
        cu.getPackage() == null ? -1 : positions.getEndPosition(cu, cu.getPackage());
    final int afterPackage = packageEnd < 0 ? 0 : (int) lineMap.getLineNumber(packageEnd);
    return Optional.of(new ImportLayout(lines, new Position(afterPackage, 0)));
  }

  private static String spelling(final ImportTree imp) {
    final String name = imp.getQualifiedIdentifier().toString();
    return imp.isStatic() ? ImportLayout.staticSpelling(name) : name;
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

    return layout().map(found -> found.edits(List.of(qualifiedName)).get(0)).orElse(null);
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
