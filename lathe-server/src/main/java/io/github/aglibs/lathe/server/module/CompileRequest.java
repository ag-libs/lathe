package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.server.analysis.CompileMode;

public record CompileRequest(String uri, String content, long generation, CompileMode mode) {}
