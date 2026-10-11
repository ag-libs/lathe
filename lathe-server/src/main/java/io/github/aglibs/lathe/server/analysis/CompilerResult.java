package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public record CompilerResult(
    List<Diagnostic<? extends JavaFileObject>> diagnostics,
    AttributedFileAnalysis fileAnalysis,
    Set<String> writtenBinaryNames,
    // No error from javac's parse phase: the file's declarations are whole, so it can stand in for
    // its written classes in another file's analysis.
    boolean parsedCleanly) {
  public CompilerResult {
    ValidCheck.check()
        .notNull(diagnostics, "diagnostics")
        .notNull(fileAnalysis, "fileAnalysis")
        .notNull(writtenBinaryNames, "writtenBinaryNames")
        .validate();
    diagnostics = List.copyOf(diagnostics);
    writtenBinaryNames = Set.copyOf(writtenBinaryNames);
  }

  // A write, or a result with no parse of its own to report on.
  public CompilerResult(
      final List<Diagnostic<? extends JavaFileObject>> diagnostics,
      final AttributedFileAnalysis fileAnalysis,
      final Set<String> writtenBinaryNames) {
    this(diagnostics, fileAnalysis, writtenBinaryNames, true);
  }
}
