package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;

public record TransientAnalysis(String uri, AttributedFileAnalysis analysis) {
  public TransientAnalysis {
    ValidCheck.check().notNull(uri, "uri").notNull(analysis, "analysis").validate();
  }
}
