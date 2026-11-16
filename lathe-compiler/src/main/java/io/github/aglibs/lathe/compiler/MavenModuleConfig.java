package io.github.aglibs.lathe.compiler;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.install.CompileCapture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.codehaus.plexus.compiler.CompilerConfiguration;

// The lsp-params record for one maven-compiler-plugin compile, read off its Plexus configuration.
final class MavenModuleConfig {

  private MavenModuleConfig() {}

  static ModuleConfigData of(final CompilerConfiguration config) {
    final var genSources = config.getGeneratedSourcesDirectory();
    final String sourceTree = LatheCompiler.sourceTree(config);
    return new ModuleConfigData(
        sourceTree,
        config.getOutputLocation(),
        genSources != null ? genSources.toString() : null,
        nullToEmpty(config.getSourceLocations()),
        nullToEmpty(config.getClasspathEntries()),
        nullToEmpty(config.getModulepathEntries()),
        nullToEmpty(config.getProcessorPathEntries()),
        config.getReleaseVersion(),
        config.getSourceEncoding() != null ? config.getSourceEncoding() : "UTF-8",
        config.isParameters(),
        enablePreview(config),
        config.getProc(),
        targetsNewerThanMaven(config.getTargetVersion())
            ? compilerArgs(config)
            : CompileCapture.withCompileJdk(
                compilerArgs(config),
                compileJdk(config),
                config.getSourceVersion(),
                config.getTargetVersion(),
                config.getReleaseVersion(),
                enablePreview(config)),
        AnalysisMode.CLASSPATH,
        false,
        LatheLayout.TEST_CLASSES_DIR.equals(sourceTree),
        List.of());
  }

  // The server runs on the JDK Maven runs on, whose javac cannot compile for a newer toolchain's
  // target: such a compile keeps its args, and the server compiles it at its own level, as without
  // the versions. A target it cannot read ("1.8" and "17" are read) is taken as not newer.
  private static boolean targetsNewerThanMaven(final String target) {
    if (target == null) {
      return false;
    }

    final String version = target.startsWith("1.") ? target.substring(2) : target;
    final int dot = version.indexOf('.');
    try {
      return Integer.parseInt(dot < 0 ? version : version.substring(0, dot))
          > Runtime.version().feature();
    } catch (final NumberFormatException e) {
      return false;
    }
  }

  // A toolchain makes maven-compiler-plugin fork its JDK's javac (bin/javac under its home);
  // otherwise javac runs in Maven's own JVM.
  private static Path compileJdk(final CompilerConfiguration config) {
    final String executable = config.getExecutable();
    if (config.isFork() && executable != null && Path.of(executable).isAbsolute()) {
      final Path bin = Path.of(executable).getParent();
      return bin != null ? bin.getParent() : null;
    }

    return Path.of(System.getProperty(LatheLayout.JAVA_HOME_PROPERTY));
  }

  private static List<String> compilerArgs(final CompilerConfiguration config) {
    return config.getCustomCompilerArgumentsEntries().stream()
        .flatMap(MavenModuleConfig::streamCompilerArg)
        .toList();
  }

  private static Stream<String> streamCompilerArg(final Map.Entry<String, String> e) {
    final var key = e.getKey();
    final var val = e.getValue();
    final var hasVal = val != null && !val.isBlank();
    if (key == null) {
      // standalone arg stored with null key (e.g. from <compilerArgs> in some Maven versions)
      return hasVal ? Stream.of(val) : Stream.empty();
    }
    return hasVal ? Stream.of(key, val) : Stream.of(key);
  }

  // isEnablePreview() was added in plexus-compiler-api 2.9.0; older maven-compiler-plugin
  // versions (e.g. 3.8.1) ship 2.8.4 and will throw NoSuchMethodError at runtime.
  private static boolean enablePreview(final CompilerConfiguration config) {
    try {
      return config.isEnablePreview();
    } catch (final NoSuchMethodError e) {
      return false;
    }
  }

  private static List<String> nullToEmpty(final List<String> list) {
    return list != null ? list : List.of();
  }
}
