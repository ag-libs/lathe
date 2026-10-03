package io.github.aglibs.lathe.core.typeindex;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Derives the primary type a {@code .java} file declares from its path alone — package from the
 * source-root-relative directory, simple name from the file name. No parsing, so {@link TypeKind}
 * is {@code UNKNOWN} and supertypes are empty; it indexes the file's conventional public top-level
 * type for name-based lookup (workspace symbols, type-name completion) before the file is compiled.
 * The complete, kind-accurate entry arrives from {@link ClassFileTypeScanner} once the file is
 * built.
 */
public final class SourceTypeScanner {

  private static final String JAVA_SUFFIX = ".java";

  private SourceTypeScanner() {}

  public static Optional<TypeIndexEntry> deriveEntry(final Path sourceRoot, final Path javaFile) {
    if (!javaFile.startsWith(sourceRoot)) {
      return Optional.empty();
    }

    final String fileName = javaFile.getFileName().toString();
    if (!fileName.endsWith(JAVA_SUFFIX)) {
      return Optional.empty();
    }

    final String simpleName = fileName.substring(0, fileName.length() - JAVA_SUFFIX.length());
    if (simpleName.isBlank()
        || "module-info".equals(simpleName)
        || "package-info".equals(simpleName)) {
      return Optional.empty();
    }

    final String packageName = packageOf(sourceRoot.relativize(javaFile).getParent());
    final String binaryName =
        packageName.isEmpty() ? simpleName : "%s.%s".formatted(packageName, simpleName);
    return Optional.of(
        new TypeIndexEntry(simpleName, binaryName, packageName, TypeKind.UNKNOWN, true, List.of()));
  }

  private static String packageOf(final Path relativeDir) {
    if (relativeDir == null) {
      return "";
    }

    return IntStream.range(0, relativeDir.getNameCount())
        .mapToObj(relativeDir::getName)
        .map(Path::toString)
        .collect(Collectors.joining("."));
  }
}
