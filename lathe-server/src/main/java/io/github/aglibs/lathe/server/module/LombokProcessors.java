package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import javax.annotation.processing.Processor;

// Lombok, for the compiles that only analyze, when a module has it on its processor path. Those
// compiles run no other processor, but Lombok changes the class it compiles in place: without it,
// every member it generates reads as missing. One class loader serves every analysis of the module;
// javac's own, built from the processor path per compile, loads Lombok again each time (~110 ms).
final class LombokProcessors implements AutoCloseable {

  // The service file javac reads a jar's processors from. Lombok's jar lists its own there, all in
  // its lombok package, so no class name of Lombok's is assumed.
  private static final String PROCESSOR_SERVICES = "META-INF/services/" + Processor.class.getName();
  private static final String LOMBOK_PACKAGE = "lombok.";

  private final URLClassLoader loader;
  private final List<ServiceLoader.Provider<Processor>> providers;

  private LombokProcessors(final URLClassLoader loader) {
    this.loader = loader;
    // Every processor Lombok lists, as javac runs them: without its claiming one, a build on
    // -Xlint:all -Werror fails on Lombok's annotations being claimed by none. Only Lombok's: a
    // processor the parent class loader sees must not run.
    this.providers =
        loader == null
            ? List.of()
            : ServiceLoader.load(Processor.class, loader).stream()
                .filter(provider -> isLombok(provider.type().getName()))
                .toList();
  }

  static LombokProcessors none() {
    return new LombokProcessors(null);
  }

  // Lombok's jar on the processor path, loaded under javac's own class loader (parent), as javac
  // loads processors; none when the path has no Lombok.
  static LombokProcessors find(final List<Path> processorPath, final ClassLoader parent) {
    return processorPath.stream()
        .filter(LombokProcessors::listsLombokProcessors)
        .findFirst()
        .map(jar -> new LombokProcessors(new URLClassLoader(new URL[] {url(jar)}, parent)))
        .orElseGet(LombokProcessors::none);
  }

  boolean present() {
    return !providers.isEmpty();
  }

  // Fresh processors for one compile; javac initializes each for its own task.
  List<Processor> create() {
    return providers.stream().map(ServiceLoader.Provider::get).toList();
  }

  @Override
  public void close() throws IOException {
    if (loader != null) {
      loader.close();
    }
  }

  private static boolean listsLombokProcessors(final Path path) {
    return Files.isRegularFile(path)
        && path.getFileName().toString().endsWith(".jar")
        && IOUtil.unchecked(() -> FileUtil.readEntry(path, PROCESSOR_SERVICES))
            .map(
                services ->
                    services.lines().map(String::strip).anyMatch(LombokProcessors::isLombok))
            .orElse(false);
  }

  private static boolean isLombok(final String className) {
    return className.startsWith(LOMBOK_PACKAGE);
  }

  private static URL url(final Path jar) {
    return IOUtil.unchecked(() -> jar.toUri().toURL());
  }
}
