package io.github.aglibs.lathe.core.typeindex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class ClassFileTypeScanner {

  private static final String CLASS_SUFFIX = ".class";
  private static final String META_INF = "META-INF/";
  private static final String META_INF_VERSIONS = "META-INF/versions/";

  private ClassFileTypeScanner() {}

  public static List<TypeIndexEntry> scanJar(final Path jar) throws IOException {
    try (final JarFile jarFile =
        new JarFile(jar.toFile(), false, JarFile.OPEN_READ, Runtime.version())) {
      final boolean multiRelease = jarFile.isMultiRelease();
      try (final Stream<JarEntry> entries = entries(jarFile)) {
        return sorted(
            entries.flatMap(entry -> scanJarEntry(jarFile, entry, multiRelease).stream()));
      }
    }
  }

  public static List<TypeIndexEntry> scanDirectory(final Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }

    try (final Stream<Path> files = Files.walk(root)) {
      return sorted(
          files.filter(Files::isRegularFile).flatMap(path -> scanClassFile(root, path).stream()));
    }
  }

  private static Stream<JarEntry> entries(final JarFile jarFile) {
    return jarFile.isMultiRelease() ? jarFile.versionedStream() : jarFile.stream();
  }

  private static Optional<TypeIndexEntry> scanJarEntry(
      final JarFile jarFile, final JarEntry entry, final boolean multiRelease) {
    final Optional<String> className = standardClassEntryName(entry, multiRelease);
    if (className.isEmpty()) {
      return Optional.empty();
    }

    try (final InputStream in = jarFile.getInputStream(entry)) {
      return scanClassEntry(className.get(), in);
    } catch (final IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Optional<TypeIndexEntry> scanClassFile(final Path root, final Path file) {
    final Optional<String> className = standardClassEntryName(classEntryName(root, file), false);
    if (className.isEmpty()) {
      return Optional.empty();
    }

    try (final InputStream in = Files.newInputStream(file)) {
      return scanClassEntry(className.get(), in);
    } catch (final IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Optional<TypeIndexEntry> scanClassEntry(
      final String className, final InputStream in) throws IOException {
    final Optional<ClassMetadata> metadata = ClassMetadataReader.read(in);
    if (metadata.isEmpty() || !className(metadata.get()).equals(className)) {
      return Optional.empty();
    }

    return Optional.of(toEntry(metadata.get()));
  }

  private static String classEntryName(final Path root, final Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  private static List<TypeIndexEntry> sorted(final Stream<TypeIndexEntry> entries) {
    return entries.sorted(Comparator.comparing(TypeIndexEntry::qualifiedName)).toList();
  }

  private static Optional<String> standardClassEntryName(
      final JarEntry entry, final boolean multiRelease) {
    if (entry.isDirectory()) {
      return Optional.empty();
    }

    return standardClassEntryName(entry.getName(), multiRelease);
  }

  private static Optional<String> standardClassEntryName(
      final String rawName, final boolean multiRelease) {
    final String name = multiRelease ? normalizeMultiReleaseName(rawName) : rawName;
    if (name.startsWith(META_INF)
        || !name.endsWith(CLASS_SUFFIX)
        || name.endsWith("module-info.class")
        || name.endsWith("package-info.class")) {
      return Optional.empty();
    }

    return Optional.of(name);
  }

  private static String normalizeMultiReleaseName(final String name) {
    if (!name.startsWith(META_INF_VERSIONS)) {
      return name;
    }

    final String rest = name.substring(META_INF_VERSIONS.length());
    final int versionEnd = rest.indexOf('/');
    if (versionEnd < 0 || !isVersionNumber(rest.substring(0, versionEnd))) {
      return name;
    }

    return rest.substring(versionEnd + 1);
  }

  private static boolean isVersionNumber(final String text) {
    return !text.isBlank() && text.chars().allMatch(Character::isDigit);
  }

  private static String className(final ClassMetadata metadata) {
    return metadata.binaryName().replace('.', '/') + CLASS_SUFFIX;
  }

  private static TypeIndexEntry toEntry(final ClassMetadata metadata) {
    final String binaryName = metadata.binaryName();
    final int packageEnd = binaryName.lastIndexOf('.');
    final String packageName = packageEnd > 0 ? binaryName.substring(0, packageEnd) : "";
    final int nestedNameStart = binaryName.lastIndexOf('$') + 1;
    final int simpleNameStart = Math.max(packageEnd + 1, nestedNameStart);
    final String simpleName = binaryName.substring(simpleNameStart);
    final boolean typeNameCandidate = !binaryName.contains("$") && metadata.access().isPublicType();
    final TypeKind kind = metadata.access().kind();
    final List<String> directSupertypes =
        kind == TypeKind.INTERFACE || kind == TypeKind.ANNOTATION
            ? metadata.directSupertypes().stream()
                .filter(name -> !"java.lang.Object".equals(name))
                .toList()
            : metadata.directSupertypes();
    return new TypeIndexEntry(
        simpleName, binaryName, packageName, kind, typeNameCandidate, directSupertypes);
  }
}
