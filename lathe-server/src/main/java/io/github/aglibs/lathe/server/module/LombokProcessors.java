package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.processing.Processor;

// Lombok, for the compiles that only analyze, when a module has it on its processor path. Those
// compiles run no other processor, but Lombok changes the class it compiles in place: without it,
// every member it generates reads as missing. One class loader serves every analysis of the module;
// javac's own, built from the processor path per compile, loads Lombok again each time (~110 ms).
final class LombokProcessors implements AutoCloseable {

  private static final String MARKER = "lombok/launch/AnnotationProcessorHider.class";
  // Both of Lombok's processors, as javac would discover them: without the claiming one, a build on
  // -Xlint:all -Werror fails on Lombok's annotations being claimed by no processor.
  private static final List<String> PROCESSORS =
      List.of(
          "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
          "lombok.launch.AnnotationProcessorHider$ClaimingProcessor");

  private final URLClassLoader loader;

  private LombokProcessors(final URLClassLoader loader) {
    this.loader = loader;
  }

  static LombokProcessors none() {
    return new LombokProcessors(null);
  }

  // Lombok's jar on the processor path, loaded under javac's own class loader (parent), as javac
  // loads processors; none when the path has no Lombok.
  static LombokProcessors find(final List<Path> processorPath, final ClassLoader parent) {
    return processorPath.stream()
        .filter(LombokProcessors::isLombokJar)
        .findFirst()
        .map(jar -> new LombokProcessors(new URLClassLoader(new URL[] {url(jar)}, parent)))
        .orElseGet(LombokProcessors::none);
  }

  boolean present() {
    return loader != null;
  }

  // Fresh processors for one compile; javac initializes each for its own task.
  List<Processor> create() {
    return PROCESSORS.stream().map(this::instantiate).toList();
  }

  @Override
  public void close() throws IOException {
    if (loader != null) {
      loader.close();
    }
  }

  private Processor instantiate(final String className) {
    try {
      return (Processor) loader.loadClass(className).getDeclaredConstructor().newInstance();
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("cannot load Lombok's %s".formatted(className), e);
    }
  }

  private static boolean isLombokJar(final Path path) {
    return Files.isRegularFile(path)
        && path.getFileName().toString().endsWith(".jar")
        && !IOUtil.unchecked(() -> FileUtil.listEntries(path, MARKER::equals)).isEmpty();
  }

  private static URL url(final Path jar) {
    return IOUtil.unchecked(() -> jar.toUri().toURL());
  }
}
