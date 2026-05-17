package io.github.aglibs.lathe.server.analysis.completion;

interface CompletionStrategy {
  CompletionResult attempt(CompletionRequest request);
}
