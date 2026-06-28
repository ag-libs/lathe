package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public record CompilerResult(
    List<Diagnostic<? extends JavaFileObject>> diagnostics,
    AttributedFileAnalysis fileAnalysis,
    Set<String> writtenBinaryNames) {
  public CompilerResult {
    ValidCheck.check()
        .notNull(diagnostics, "diagnostics")
        .notNull(fileAnalysis, "fileAnalysis")
        .notNull(writtenBinaryNames, "writtenBinaryNames")
        .validate();
    diagnostics = List.copyOf(diagnostics);
    writtenBinaryNames = Set.copyOf(writtenBinaryNames);
  }
}
