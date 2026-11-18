package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.CompilationUnitTree;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * A source file's package must name its directory under its source root. javac compiles one that
 * does not, but Lathe finds a type's file from its package (definition, rename, the class outputs),
 * so such a file gets an error saying so, as Eclipse gives one, instead of answers that miss it.
 */
public final class PackageDirectory {

  public static final String CODE = "lathe.package.directory";

  private static final String NEED = "Lathe's navigation, rename and checks need them to match.";

  private PackageDirectory() {}

  /**
   * The error for {@code file}, when the package its compilation unit declares is not the one its
   * directory under the innermost of {@code sourceRoots} holding it names; none for a file no root
   * holds.
   */
  public static Optional<Diagnostic> mismatch(
      final AttributedFileAnalysis analysis, final Path file, final List<Path> sourceRoots) {
    final Optional<Path> root =
        sourceRoots.stream()
            .filter(file::startsWith)
            .max(Comparator.comparingInt(Path::getNameCount));
    if (root.isEmpty() || analysis.tree() == null) {
      return Optional.empty();
    }

    final String expected = expectedPackage(root.get().relativize(file).getParent());
    final CompilationUnitTree cu = analysis.tree();
    final String declared = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
    if (declared.equals(expected)) {
      return Optional.empty();
    }

    final Range range =
        cu.getPackage() == null
            ? new Range(new Position(0, 0), new Position(0, 0))
            : Positions.range(analysis.trees(), cu, cu.getPackage());
    final var diagnostic =
        new Diagnostic(range, message(declared, expected), DiagnosticSeverity.Error, "lathe");
    diagnostic.setCode(CODE);
    return Optional.of(diagnostic);
  }

  private static String expectedPackage(final Path directory) {
    return directory == null
        ? ""
        : StreamSupport.stream(directory.spliterator(), false)
            .map(Path::toString)
            .collect(Collectors.joining("."));
  }

  private static String message(final String declared, final String expected) {
    if (declared.isEmpty()) {
      return "The file declares no package, but its directory is package %s: %s"
          .formatted(expected, NEED);
    }

    if (expected.isEmpty()) {
      return "The declared package %s does not match the source root the file is in, which is no package: %s"
          .formatted(declared, NEED);
    }

    return "The declared package %s does not match the directory, package %s: %s"
        .formatted(declared, expected, NEED);
  }
}
