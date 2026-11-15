package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.SourcePositions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.TextEdit;

public final class ImportAnalyzer {

  private static final long NO_IMPORT = -1;
  private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t\\r]*\\n");

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
    // An import still being typed may have no end position yet.
    final List<? extends ImportTree> complete =
        cu.getImports().stream().filter(imp -> positions.getEndPosition(cu, imp) >= 0).toList();
    final String source = DeclarationNames.source(cu).orElse("");
    final List<ImportLayout.ImportLine> lines =
        IntStream.range(0, complete.size())
            .mapToObj(
                i ->
                    importLine(
                        complete.get(i),
                        i == 0 ? NO_IMPORT : positions.getEndPosition(cu, complete.get(i - 1)),
                        source))
            .toList();
    final long packageEnd =
        cu.getPackage() == null ? -1 : positions.getEndPosition(cu, cu.getPackage());
    return Optional.of(
        new ImportLayout(
            lines,
            packageEnd < 0 ? ImportLayout.NO_PACKAGE : Positions.offsetToPosition(cu, packageEnd)));
  }

  // An import, and whether a blank line separates it from the one ending at previousEnd: a comment
  // line or an import still being typed between them does not.
  private ImportLayout.ImportLine importLine(
      final ImportTree imp, final long previousEnd, final String source) {
    final CompilationUnitTree cu = analysis.tree();
    final SourcePositions positions = analysis.trees().getSourcePositions();
    final long start = positions.getStartPosition(cu, imp);
    return new ImportLayout.ImportLine(
        spelling(imp),
        Positions.offsetToPosition(cu, start),
        Positions.offsetToPosition(cu, positions.getEndPosition(cu, imp)),
        previousEnd != NO_IMPORT
            && start <= source.length()
            && BLANK_LINE.matcher(source.substring((int) previousEnd, (int) start)).find());
  }

  private static String spelling(final ImportTree imp) {
    return ImportLayout.spelling(imp.getQualifiedIdentifier().toString(), imp.isStatic());
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

    return layout().map(found -> found.edit(qualifiedName)).orElse(null);
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
