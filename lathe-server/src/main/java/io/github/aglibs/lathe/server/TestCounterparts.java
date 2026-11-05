package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

// A lathe.testCounterparts answer: for a main file the tests of its type, for a test file the type
// it tests, best match first. Found by the names the build's test runs pick up by default
// (Surefire's and Failsafe's), in any module, so a test in a separate test module is found too.
record TestCounterparts(boolean fromTest, List<Target> targets) {

  private static final List<String> SUFFIXES = List.of("Test", "Tests", "TestCase", "IT", "ITCase");
  private static final List<String> PREFIXES = List.of("Test", "IT");

  TestCounterparts {
    ValidCheck.check().notNull(targets, "targets").validate();
    targets = List.copyOf(targets);
  }

  record Target(String uri, String qualifiedName) {

    Target {
      ValidCheck.check().notBlank(uri, "uri").notBlank(qualifiedName, "qualifiedName").validate();
    }
  }

  private record Candidate(TypeIndexEntry entry, Path file, ModuleSourceConfig config, int rank) {}

  // The counterparts of the file's top-level type (its file name), from the other tree: the test
  // trees for a main file, the main trees for a test file. Ranked by same package, then same
  // module, then the convention the name follows (FooTest before FooIT).
  static TestCounterparts find(
      final Path file,
      final String pkg,
      final Function<Path, Optional<ModuleSourceConfig>> configOf,
      final WorkspaceTypeIndex index,
      final List<Path> sourceRoots) {
    final Optional<ModuleSourceConfig> own = configOf.apply(file);
    if (own.isEmpty()) {
      return new TestCounterparts(false, List.of());
    }

    final boolean fromTest = own.get().isTestTree();
    final String stem = FileUtil.javaTypeName(file);
    final List<String> names = fromTest ? subjectNames(stem) : testNames(stem);
    final List<Target> targets =
        names.stream()
            .flatMap(name -> candidates(name, names.indexOf(name), configOf, index, sourceRoots))
            .filter(candidate -> candidate.config().isTestTree() != fromTest)
            .sorted(
                Comparator.comparing(
                        (Candidate candidate) -> !candidate.entry().packageName().equals(pkg))
                    .thenComparing(
                        candidate -> !candidate.config().moduleDir().equals(own.get().moduleDir()))
                    .thenComparingInt(Candidate::rank)
                    .thenComparing(candidate -> candidate.entry().binaryName()))
            .map(
                candidate ->
                    new Target(LatheUri.fromPath(candidate.file()), candidate.entry().binaryName()))
            .distinct()
            .toList();
    return new TestCounterparts(fromTest, targets);
  }

  // The project's top-level types named so, with their source file and the config compiling it.
  private static Stream<Candidate> candidates(
      final String name,
      final int rank,
      final Function<Path, Optional<ModuleSourceConfig>> configOf,
      final WorkspaceTypeIndex index,
      final List<Path> sourceRoots) {
    return index.searchExact(name).stream()
        .filter(entry -> entry.simpleName().equals(name))
        .filter(entry -> !entry.binaryName().contains("$"))
        .filter(entry -> index.isReactorType(entry.binaryName()))
        .flatMap(
            entry ->
                TypeSourceLocator.findSourceFile(entry, sourceRoots).stream()
                    .flatMap(
                        source ->
                            configOf.apply(source).stream()
                                .map(config -> new Candidate(entry, source, config, rank))));
  }

  // FooTest, FooTests, FooTestCase, FooIT, FooITCase, TestFoo, ITFoo.
  static List<String> testNames(final String stem) {
    return Stream.concat(
            SUFFIXES.stream().map(suffix -> "%s%s".formatted(stem, suffix)),
            PREFIXES.stream().map(prefix -> "%s%s".formatted(prefix, stem)))
        .toList();
  }

  // The names a test's name strips to: FooTest, TestFoo and FooIT all give Foo.
  static List<String> subjectNames(final String stem) {
    return Stream.concat(
            SUFFIXES.stream()
                .filter(suffix -> stem.endsWith(suffix) && stem.length() > suffix.length())
                .map(suffix -> stem.substring(0, stem.length() - suffix.length())),
            PREFIXES.stream()
                .filter(
                    prefix ->
                        stem.startsWith(prefix)
                            && stem.length() > prefix.length()
                            && Character.isUpperCase(stem.charAt(prefix.length())))
                .map(prefix -> stem.substring(prefix.length())))
        .distinct()
        .toList();
  }
}
