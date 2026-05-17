package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SourceCompiler;
import java.util.logging.Logger;

final class RepairStrategy implements CompletionStrategy {

  private static final Logger LOG = Logger.getLogger(RepairStrategy.class.getName());

  private final SourceCompiler compiler;
  private final SourceRepairer repairer;

  RepairStrategy(final SourceCompiler compiler, final SourceRepairer repairer) {
    this.compiler = compiler;
    this.repairer = repairer;
  }

  @Override
  public CompletionResult attempt(final CompletionRequest request) {
    final var injected =
        request.source().substring(0, request.offset())
            + SentinelStrategy.SENTINEL
            + "()"
            + request.source().substring(request.offset());
    final var compiled = compiler.compile(request.uri(), injected, CompileMode.FAST);

    final var repaired = repairer.repair(injected, compiled.diagnostics());
    if (repaired.isEmpty()) {
      LOG.fine(() -> "[completion] repair declined");
      return new CompletionResult.Declined();
    }

    LOG.fine(() -> "[completion] repair applied, recompiling");
    final var repairedAnalysis =
        compiler.compile(request.uri(), repaired.get(), CompileMode.FAST).fileAnalysis();
    return SentinelStrategy.resolveFromAnalysis(repairedAnalysis);
  }
}
