package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;

public record CachedAnalysis(String content, FileAnalysis analysis) {
  public CachedAnalysis {
    ValidCheck.check().notNull(content, "content").notNull(analysis, "analysis").validate();
  }
}
