package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public record CompilerResult(
    List<Diagnostic<? extends JavaFileObject>> diagnostics, AttributedFileAnalysis fileAnalysis) {
  public CompilerResult {
    ValidCheck.check()
        .notNull(diagnostics, "diagnostics")
        .notNull(fileAnalysis, "fileAnalysis")
        .validate();
  }
}
