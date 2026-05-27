package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public record CompilerResult(
    List<Diagnostic<? extends JavaFileObject>> diagnostics, AttributedFileAnalysis fileAnalysis) {}
