package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;

final class ReferenceCandidatePlanner {

  private static final Logger LOG = Logger.getLogger(ReferenceCandidatePlanner.class.getName());

  private final ReferenceCandidateIndex index;
  private final WorkspaceTypeIndex typeIndex;

  ReferenceCandidatePlanner(
      final ReferenceCandidateIndex index, final WorkspaceTypeIndex typeIndex) {
    this.index = index;
    this.typeIndex = typeIndex;
  }

  Set<String> planCandidates(final ModuleSourceConfig config, final ReferenceTarget target) {
    final var kind = target.kind();
    // A constructor's simple name is javac's "<init>", which no source file ever spells; key
    // candidate discovery on the declaring type's simple name instead (FR-013).
    final var lookupName =
        kind == ElementKind.CONSTRUCTOR
            ? simpleNameOf(target.qualifiedName())
            : target.simpleName();
    final Set<String> simpleCandidates = index.candidateUris(lookupName);
    if (simpleCandidates.isEmpty()) {
      return Set.of();
    }

    if (kind == ElementKind.LOCAL_VARIABLE
        || kind == ElementKind.PARAMETER
        || kind == ElementKind.EXCEPTION_PARAMETER
        || kind == ElementKind.RESOURCE_VARIABLE) {
      return simpleCandidates;
    }

    if (kind.isClass() || kind.isInterface()) {
      return planTypeCandidates(config, target, simpleCandidates);
    }

    // Constructors and enum constants are not inherited, so they resolve only through their
    // declaring type's simple name (plus any static-import spelling).
    if (kind == ElementKind.CONSTRUCTOR || kind == ElementKind.ENUM_CONSTANT) {
      return narrowToFamily(Set.of(target.qualifiedName()), target, simpleCandidates);
    }

    if (kind == ElementKind.FIELD) {
      return narrowToFamily(overrideFamily(target), target, simpleCandidates);
    }

    if (kind == ElementKind.METHOD) {
      return planMethodCandidates(target, simpleCandidates);
    }

    return simpleCandidates;
  }

  private Set<String> planTypeCandidates(
      final ModuleSourceConfig config,
      final ReferenceTarget target,
      final Set<String> simpleCandidates) {
    final var qualName = target.qualifiedName();
    final int lastDot = qualName.lastIndexOf('.');
    final var targetPkg = lastDot > 0 ? qualName.substring(0, lastDot) : "";

    // java.lang types are implicitly imported, so we must search all files containing the simple
    // name
    if ("java.lang".equals(targetPkg)) {
      return simpleCandidates;
    }

    final Path packageRel = packageRelForPackageName(targetPkg);

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

    final List<Path> packageRoots = packageSearchRoots(config);
    return Stream.concat(
            importTokens.flatMap(token -> index.candidateUris(token).stream()),
            simpleCandidates.stream()
                .filter(uri -> isInPackage(LatheUri.toPath(uri), packageRoots, packageRel)))
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * A method resolves through its declaring type, the supertypes whose method it overrides (reactor
   * or dependency), and its overriding subtypes. Narrowing candidates to that override family —
   * declaring type, every overridden declarer's simple name, and subtypes — avoids compiling every
   * file that merely spells the simple name, while still reaching polymorphic call sites through an
   * interface-typed receiver such as {@code baseConfig.name()}. Two cases still need the broad
   * search: a target reconstructed from a call-hierarchy item ({@code !overrideFamilyBounded}), and
   * an {@code Object} method ({@code equals}/{@code hashCode}/{@code toString}) — every type is an
   * {@code Object} and receivers are rarely spelled {@code Object}, so family narrowing would drop
   * most genuine call sites.
   */
  private Set<String> planMethodCandidates(
      final ReferenceTarget target, final Set<String> simpleCandidates) {
    if (!target.overrideFamilyBounded()) {
      return simpleCandidates;
    }

    if (target.overriddenDeclarers().contains("java.lang.Object")) {
      LOG.fine(
          () ->
              "[references] %s overrides java.lang.Object — broad candidate search"
                  .formatted(target.simpleName()));
      return simpleCandidates;
    }

    return narrowToFamily(overrideFamily(target), target, simpleCandidates);
  }

  /**
   * The declaring type, the supertypes whose method the target overrides (empty for fields and
   * non-overriding methods), and the declaring type's subtypes. Fields inherit down the hierarchy
   * but are never overridden, so their family is simply declaring type plus subtypes.
   */
  private Set<String> overrideFamily(final ReferenceTarget target) {
    return Stream.concat(
            Stream.concat(Stream.of(target.qualifiedName()), target.overriddenDeclarers().stream()),
            subtypeBinaryNames(target.qualifiedName()))
        .collect(Collectors.toUnmodifiableSet());
  }

  private Stream<String> subtypeBinaryNames(final String binaryName) {
    return typeIndex.transitiveSubtypes(binaryName).stream().map(TypeIndexEntry::binaryName);
  }

  /**
   * Keeps only the files that spell both the member's simple name and the simple name of a family
   * type, always unioning in static-import spelling sites ({@code import static Owner.member}).
   */
  private Set<String> narrowToFamily(
      final Set<String> familyBinaryNames,
      final ReferenceTarget target,
      final Set<String> simpleCandidates) {
    final Set<String> familyFiles =
        familyBinaryNames.stream()
            .map(ReferenceCandidatePlanner::simpleNameOf)
            .flatMap(name -> index.candidateUris(name).stream())
            .collect(Collectors.toUnmodifiableSet());
    final Stream<String> staticImportSites =
        Stream.concat(
            index
                .candidateUris("%s.%s".formatted(target.qualifiedName(), target.simpleName()))
                .stream(),
            index.candidateUris(target.qualifiedName() + ".*").stream());
    return Stream.concat(simpleCandidates.stream().filter(familyFiles::contains), staticImportSites)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String simpleNameOf(final String binaryName) {
    final int cut = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
    return cut < 0 ? binaryName : binaryName.substring(cut + 1);
  }

  /**
   * The regular source roots plus the generated-sources root (when present). Generated companions
   * such as a record's {@code @Builder} live in the annotation-processor output root and reference
   * the record by simple name only (same package, no import), so the same-package filter must
   * consider that root too (FR-012).
   */
  static List<Path> packageSearchRoots(final ModuleSourceConfig config) {
    if (config.originalGenSourcesDir() == null) {
      return config.sourceRoots();
    }

    return Stream.concat(config.sourceRoots().stream(), Stream.of(config.originalGenSourcesDir()))
        .toList();
  }

  private static boolean isInPackage(
      final Path path, final List<Path> sourceRoots, final Path packageRel) {
    return sourceRoots.stream()
        .map(root -> root.resolve(packageRel))
        .anyMatch(dir -> path.getParent().equals(dir));
  }

  static Path packageRelForQualifiedName(final String qualifiedName) {
    final int packageEnd = qualifiedName.lastIndexOf('.');
    if (packageEnd < 0) {
      return Path.of("");
    }

    return packageRelForPackageName(qualifiedName.substring(0, packageEnd));
  }

  private static Path packageRelForPackageName(final String packageName) {
    return packageName.isEmpty() ? Path.of("") : Path.of(packageName.replace('.', '/'));
  }
}
