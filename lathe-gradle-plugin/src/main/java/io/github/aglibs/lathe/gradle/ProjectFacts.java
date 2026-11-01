package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// What one project contributes to the workspace files. Each project writes its own, and the root's
// latheWorkspace merges them, so no project reads another's model (Isolated Projects). javaHome is
// the
// JDK its main compile runs on, absent for a project without Java. The facts task adds the rest as
// it runs: the source trees it has Java sources for, which the sync keeps in .lathe/; the external
// libraries it compiles against; the classpath to compile their sources with; and what replays its
// tests beside the test classpath (the test runner and the JUnit launcher).
record ProjectFacts(
    String module,
    List<OutputData> outputs,
    List<ResourceRootData> resourceRoots,
    String javaHome,
    int javaVersion,
    List<String> accessFlags,
    List<String> trees,
    List<DependencyFact> dependencies,
    List<String> dependencyClasspath,
    List<String> runnerClasspath) {

  ProjectFacts {
    ValidCheck.check().notNull(module, "module").validate();
    outputs = outputs != null ? List.copyOf(outputs) : List.of();
    resourceRoots = resourceRoots != null ? List.copyOf(resourceRoots) : List.of();
    accessFlags = accessFlags != null ? List.copyOf(accessFlags) : List.of();
    trees = trees != null ? List.copyOf(trees) : List.of();
    dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
    dependencyClasspath =
        dependencyClasspath != null ? List.copyOf(dependencyClasspath) : List.of();
    runnerClasspath = runnerClasspath != null ? List.copyOf(runnerClasspath) : List.of();
  }

  // A project without Java: its directory only.
  static ProjectFacts of(final String module) {
    return new ProjectFacts(
        module, List.of(), List.of(), null, 0, List.of(), List.of(), List.of(), List.of(),
        List.of());
  }

  // Known only when the facts task runs: after any task that generates sources, and resolved then.
  ProjectFacts withResolved(
      final List<String> sourceTrees,
      final List<DependencyFact> libraries,
      final List<String> libraryClasspath,
      final List<String> testRunner) {
    return new ProjectFacts(
        module,
        outputs,
        resourceRoots,
        javaHome,
        javaVersion,
        accessFlags,
        sourceTrees,
        libraries,
        libraryClasspath,
        testRunner);
  }
}
