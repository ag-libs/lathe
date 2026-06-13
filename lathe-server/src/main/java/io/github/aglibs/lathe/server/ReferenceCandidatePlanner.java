package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;

final class ReferenceCandidatePlanner {

  private final ReferenceCandidateIndex index;

  ReferenceCandidatePlanner(final ReferenceCandidateIndex index) {
    this.index = index;
  }

  Set<String> planCandidates(final ModuleSourceConfig config, final ReferenceTarget target) {
    final var simpleName = target.simpleName();
    final Set<String> simpleCandidates = index.candidateUris(simpleName);
    if (simpleCandidates.isEmpty()) {
      return Set.of();
    }

    final var kind = target.kind();
    if (kind == ElementKind.LOCAL_VARIABLE
        || kind == ElementKind.PARAMETER
        || kind == ElementKind.EXCEPTION_PARAMETER
        || kind == ElementKind.RESOURCE_VARIABLE) {
      return simpleCandidates;
    }

    final var qualName = target.qualifiedName();
    if (kind.isClass() || kind.isInterface()) {
      final int lastDot = qualName.lastIndexOf('.');
      final var targetPkg = lastDot > 0 ? qualName.substring(0, lastDot) : "";

      // java.lang types are implicitly imported, so we must search all files containing the simple
      // name
      if ("java.lang".equals(targetPkg)) {
        return simpleCandidates;
      }

      final Path packageRel =
          targetPkg.isEmpty() ? Path.of("") : Path.of(targetPkg.replace('.', '/'));

      // Collect all possible import spelling candidates by walking up qualified name prefixes
      final Stream.Builder<String> tokensBuilder = Stream.builder();
      tokensBuilder.add(qualName);
      tokensBuilder.add(qualName + ".*");

      String current = qualName;
      while (true) {
        final int dot = current.lastIndexOf('.');
        if (dot <= 0) {
          break;
        }
        current = current.substring(0, dot);
        if (current.indexOf('.') > 0) {
          tokensBuilder.add(current + ".*");
        }
      }
      final Stream<String> importTokens = tokensBuilder.build();

      return Stream.concat(
              importTokens.flatMap(token -> index.candidateUris(token).stream()),
              simpleCandidates.stream()
                  .filter(uri -> isInPackage(toPath(uri), config.sourceRoots(), packageRel)))
          .collect(Collectors.toUnmodifiableSet());
    }

    if (kind == ElementKind.FIELD
        || kind == ElementKind.METHOD
        || kind == ElementKind.CONSTRUCTOR
        || kind == ElementKind.ENUM_CONSTANT) {
      final Set<String> staticImports = index.candidateUris(qualName + "." + simpleName);
      final Set<String> staticWildcards = index.candidateUris(qualName + ".*");

      final int lastDot = qualName.lastIndexOf('.');
      final var enclosingSimple = lastDot > 0 ? qualName.substring(lastDot + 1) : qualName;
      final Set<String> enclosingCandidates = index.candidateUris(enclosingSimple);

      return Stream.concat(
              Stream.concat(staticImports.stream(), staticWildcards.stream()),
              simpleCandidates.stream().filter(enclosingCandidates::contains))
          .collect(Collectors.toUnmodifiableSet());
    }

    return simpleCandidates;
  }

  private static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
  }

  private static boolean isInPackage(
      final Path path, final List<Path> sourceRoots, final Path packageRel) {
    return sourceRoots.stream()
        .map(root -> root.resolve(packageRel))
        .anyMatch(dir -> path.getParent().equals(dir));
  }
}
