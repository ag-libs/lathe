package io.github.aglibs.lathe.server.analysis;

import org.eclipse.lsp4j.Diagnostic;

public record CodeActionRequest(String uri, Diagnostic diag, DiagnosticPayload payload) {}
