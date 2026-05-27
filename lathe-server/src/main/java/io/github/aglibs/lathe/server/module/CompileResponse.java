package io.github.aglibs.lathe.server.module;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;

public record CompileResponse(String uri, long generation, List<Diagnostic> diagnostics) {}
