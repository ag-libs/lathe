package io.github.aglibs.lathe.maven.typeindex;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
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

  public List<TypeIndexEntry> scanJar(final Path jar) throws IOException {
    try (final JarFile jarFile =
        new JarFile(jar.toFile(), false, JarFile.OPEN_READ, Runtime.version())) {
      final boolean multiRelease = jarFile.isMultiRelease();
      try (final Stream<JarEntry> entries = entries(jarFile)) {
        return sorted(
            entries.flatMap(entry -> scanJarEntry(jarFile, entry, multiRelease).stream()));
      }
    }
  }

  public List<TypeIndexEntry> scanDirectory(final Path root) throws IOException {
    try (final Stream<Path> files = Files.walk(root)) {
      return sorted(
          files.filter(Files::isRegularFile).flatMap(path -> scanClassFile(root, path).stream()));
    }
  }

  private Stream<JarEntry> entries(final JarFile jarFile) {
    return jarFile.isMultiRelease() ? jarFile.versionedStream() : jarFile.stream();
  }

  private Optional<TypeIndexEntry> scanJarEntry(
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

  private Optional<TypeIndexEntry> scanClassFile(final Path root, final Path file) {
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

  private Optional<TypeIndexEntry> scanClassEntry(final String className, final InputStream in)
      throws IOException {
    final Optional<ClassAccess> access = ClassAccessReader.read(in);
    if (access.isEmpty() || !access.get().isPublicTopLevelType()) {
      return Optional.empty();
    }

    return Optional.of(toEntry(className, access.get()));
  }

  private String classEntryName(final Path root, final Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  private List<TypeIndexEntry> sorted(final Stream<TypeIndexEntry> entries) {
    return entries.sorted(Comparator.comparing(TypeIndexEntry::qualifiedName)).toList();
  }

  private Optional<String> standardClassEntryName(
      final JarEntry entry, final boolean multiRelease) {
    if (entry.isDirectory()) {
      return Optional.empty();
    }

    return standardClassEntryName(entry.getName(), multiRelease);
  }

  private Optional<String> standardClassEntryName(
      final String rawName, final boolean multiRelease) {
    final String name = multiRelease ? normalizeMultiReleaseName(rawName) : rawName;
    if (name.startsWith(META_INF)
        || !name.endsWith(CLASS_SUFFIX)
        || name.endsWith("module-info.class")
        || name.endsWith("package-info.class")
        || name.contains("$")) {
      return Optional.empty();
    }

    return Optional.of(name);
  }

  private String normalizeMultiReleaseName(final String name) {
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

  private boolean isVersionNumber(final String text) {
    return !text.isBlank() && text.chars().allMatch(Character::isDigit);
  }

  private TypeIndexEntry toEntry(final String classEntryName, final ClassAccess access) {
    final String qualifiedName =
        classEntryName
            .substring(0, classEntryName.length() - CLASS_SUFFIX.length())
            .replace('/', '.');
    final int packageEnd = qualifiedName.lastIndexOf('.');
    final String packageName = packageEnd > 0 ? qualifiedName.substring(0, packageEnd) : "";
    final String simpleName =
        packageEnd > 0 ? qualifiedName.substring(packageEnd + 1) : qualifiedName;
    return new TypeIndexEntry(simpleName, qualifiedName, packageName, access.kind());
  }
}
