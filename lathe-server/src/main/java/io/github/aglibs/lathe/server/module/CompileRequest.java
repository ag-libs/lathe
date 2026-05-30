package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.validcheck.ValidCheck;

public record CompileRequest(
    String uri, String content, int version, long generation, CompileMode mode) {
  public CompileRequest {
    ValidCheck.check().notNull(uri).notNull(content).notNull(mode).validate();
  }
}
