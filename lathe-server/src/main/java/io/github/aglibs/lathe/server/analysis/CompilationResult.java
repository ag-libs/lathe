package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public record CompilationResult(
    List<Diagnostic<? extends JavaFileObject>> diagnostics, FileAnalysis fileAnalysis) {}
