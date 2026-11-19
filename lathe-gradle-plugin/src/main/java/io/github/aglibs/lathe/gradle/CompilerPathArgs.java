package io.github.aglibs.lathe.gradle;

import io.github.aglibs.validcheck.ValidCheck;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

// A build may give javac its paths as compiler arguments rather than through the compile task's
// classpath, as Lucene's plugin does for the module path. The capture moves them into the params'
// classpath and module path, where the server maps a reactor module's output to its mirror and so
// sees the dependency, instead of passing them to javac as text.
final class CompilerPathArgs {

  private static final Set<String> MODULE_PATH_OPTIONS = Set.of("--module-path", "-p");
  private static final Set<String> CLASS_PATH_OPTIONS = Set.of("--class-path", "-classpath", "-cp");
  private static final String MODULE_PATH_EQUALS = "--module-path=";
  private static final String CLASS_PATH_EQUALS = "--class-path=";

  private CompilerPathArgs() {}

  // The arguments' classpath and module path entries, in order and normalized, so a reactor jar
  // named through `..` matches the output the manifest records; and the arguments without them.
  // Gradle's javac does not resolve a relative entry against the project, so a build gives them
  // absolute.
  record Extracted(List<String> classpath, List<String> modulepath, List<String> otherArgs) {

    Extracted {
      ValidCheck.check()
          .notNull(classpath, "classpath")
          .notNull(modulepath, "modulepath")
          .notNull(otherArgs, "otherArgs")
          .validate();
      classpath = List.copyOf(classpath);
      modulepath = List.copyOf(modulepath);
      otherArgs = List.copyOf(otherArgs);
    }
  }

  static Extracted extract(final List<String> args) {
    final var classpath = new ArrayList<String>();
    final var modulepath = new ArrayList<String>();
    final var otherArgs = new ArrayList<String>();
    for (int i = 0; i < args.size(); i++) {
      final String arg = args.get(i);
      final boolean hasValue = i + 1 < args.size();
      if (MODULE_PATH_OPTIONS.contains(arg) && hasValue) {
        modulepath.addAll(entries(args.get(++i)));
      } else if (CLASS_PATH_OPTIONS.contains(arg) && hasValue) {
        classpath.addAll(entries(args.get(++i)));
      } else if (arg.startsWith(MODULE_PATH_EQUALS)) {
        modulepath.addAll(entries(arg.substring(MODULE_PATH_EQUALS.length())));
      } else if (arg.startsWith(CLASS_PATH_EQUALS)) {
        classpath.addAll(entries(arg.substring(CLASS_PATH_EQUALS.length())));
      } else {
        otherArgs.add(arg);
      }
    }

    return new Extracted(classpath, modulepath, otherArgs);
  }

  private static List<String> entries(final String path) {
    return Arrays.stream(path.split(File.pathSeparator))
        .filter(entry -> !entry.isEmpty())
        .map(entry -> Path.of(entry).normalize().toString())
        .toList();
  }
}
