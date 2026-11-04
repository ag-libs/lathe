package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.Optional;

// Where a file's analysis runs: its module's worker, the external-sources worker, or nowhere.
sealed interface CompilerRoute {
  // The worker that analyzes the file; abstract, so a new kind of route must say whether it has
  // one.
  Optional<CompilationWorker> analysisWorker();

  record Module(CompilationWorker worker, ModuleSourceConfig config) implements CompilerRoute {

    public Module {
      ValidCheck.check().notNull(worker, "worker").notNull(config, "config").validate();
    }

    @Override
    public Optional<CompilationWorker> analysisWorker() {
      return Optional.of(worker);
    }
  }

  record External(CompilationWorker worker) implements CompilerRoute {

    public External {
      ValidCheck.check().notNull(worker, "worker").validate();
    }

    @Override
    public Optional<CompilationWorker> analysisWorker() {
      return Optional.of(worker);
    }
  }

  record Missing(String uri, String message) implements CompilerRoute {

    public Missing {
      ValidCheck.check().notNull(uri, "uri").notNull(message, "message").validate();
    }

    @Override
    public Optional<CompilationWorker> analysisWorker() {
      return Optional.empty();
    }
  }
}
