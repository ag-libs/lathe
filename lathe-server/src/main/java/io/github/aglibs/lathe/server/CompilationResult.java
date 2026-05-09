package io.github.aglibs.lathe.server;

import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

record CompilationResult(
    List<Diagnostic<? extends JavaFileObject>> diagnostics, CompilationTaskContext context) {}
