package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Where a file's analysis runs: its module's worker, the external-sources worker, or nowhere.
sealed interface CompilerRoute {
  // The worker that analyzes the file; abstract, so a new kind of route must say whether it has
  // one.
  Optional<CompilationWorker> analysisWorker();

  // The source trees a file on this route sees, by their classes dir: its own and those it compiles
  // against. Abstract, as for analysisWorker.
  Set<Path> seenTrees();

  record Module(CompilationWorker worker, ModuleSourceConfig config) implements CompilerRoute {

    public Module {
      ValidCheck.check().notNull(worker, "worker").notNull(config, "config").validate();
    }

    @Override
    public Optional<CompilationWorker> analysisWorker() {
      return Optional.of(worker);
    }

    @Override
    public Set<Path> seenTrees() {
      return Stream.concat(Stream.of(config.latheClassesDir()), config.compiledAgainst().stream())
          .collect(Collectors.toUnmodifiableSet());
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

    // A file outside every module compiles against no source tree.
    @Override
    public Set<Path> seenTrees() {
      return Set.of();
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

    @Override
    public Set<Path> seenTrees() {
      return Set.of();
    }
  }
}
