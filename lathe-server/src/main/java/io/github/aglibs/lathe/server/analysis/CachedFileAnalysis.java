package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;

public record CachedFileAnalysis(String content, int version, AttributedFileAnalysis analysis) {
  public CachedFileAnalysis {
    ValidCheck.check().notNull(content, "content").notNull(analysis, "analysis").validate();
  }
}
