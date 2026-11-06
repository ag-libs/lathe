package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.ReferenceTarget;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;

final class ReferenceCandidatePlanner {

  private final ReferenceCandidateIndex index;
  private final WorkspaceTypeIndex typeIndex;

  ReferenceCandidatePlanner(
      final ReferenceCandidateIndex index, final WorkspaceTypeIndex typeIndex) {
    this.index = index;
    this.typeIndex = typeIndex;
  }

  // The files to search first, and those to search only when one of them exposes the target's
  // family (exposureFamily) to files that never name it; deferred is empty but for a static method.
  record CandidatePlan(Set<String> primary, Set<String> deferred, Set<String> exposureFamily) {

    CandidatePlan {
      primary = Set.copyOf(primary);
      deferred = Set.copyOf(deferred);
      exposureFamily = Set.copyOf(exposureFamily);
    }
  }

  CandidatePlan planCandidates(final ModuleSourceConfig config, final ReferenceTarget target) {
    final var kind = target.kind();
    // A constructor's simple name is javac's "<init>", which no source file ever spells; key
    // candidate discovery on the declaring type's simple name instead (FR-013).
    final var lookupName =
        kind == ElementKind.CONSTRUCTOR
            ? simpleNameOf(target.qualifiedName())
            : target.simpleName();
    final Set<String> simpleCandidates = index.candidateUris(lookupName);
    if (simpleCandidates.isEmpty()) {
      return onePhase(Set.of());
    }

    if (kind == ElementKind.LOCAL_VARIABLE
        || kind == ElementKind.PARAMETER
        || kind == ElementKind.EXCEPTION_PARAMETER
        || kind == ElementKind.RESOURCE_VARIABLE) {
      return onePhase(simpleCandidates);
    }

    if (kind.isClass() || kind.isInterface()) {
      return onePhase(planTypeCandidates(config, target, simpleCandidates));
    }

    // A constructor is called only by naming its type: `new Owner()`, `Owner::new`, or a
    // subclass's super(...), whose file names the type it extends.
    if (kind == ElementKind.CONSTRUCTOR) {
      return onePhase(narrowToFamily(Set.of(target.qualifiedName()), target, simpleCandidates));
    }

    if (kind == ElementKind.METHOD && target.staticMember()) {
      return planStaticMethod(config, target, simpleCandidates);
    }

    // Instance methods, fields and enum constants (and any other unhandled kind) use the broad
    // simple-name candidate set. A use through an expression whose type the file never names
    // escapes any narrowing by type: a var bound from a getter chain, a chained call or a generic
    // type variable as receiver (FR-011), a field read as holder.owner().name, an enum constant as
    // the label `case ALPHA` of a switch over one. Some declaration hands out such a type for most
    // of them, so searching every file that spells the name is both exact and the cheaper plan; the
    // reference locator rejects the non-matches.
    return onePhase(simpleCandidates);
  }

  // A static method is called through its declaring type or a subtype by name, a static import,
  // or unqualified inside the family; only an expression of a family type reaches it from a file
  // naming none of them, and that takes a declaration exposing the family, which names it. So the
  // first phase is every file naming the family, whether or not it spells the method: each is
  // checked for such a declaration (FamilyExposure), and the rest wait for one. An interface's
  // static method is not inherited and takes no receiver, so naming the interface is the only way.
  // A library's type may be exposed by a declaration in a jar, which no phase checks: one phase.
  private CandidatePlan planStaticMethod(
      final ModuleSourceConfig config,
      final ReferenceTarget target,
      final Set<String> simpleCandidates) {
    final var owner = target.qualifiedName();
    final boolean interfaceMethod =
        typeIndex
            .findType(owner)
            .map(entry -> entry.kind() == TypeKind.INTERFACE || entry.kind() == TypeKind.ANNOTATION)
            .orElse(false);
    if (interfaceMethod) {
      return onePhase(narrowToFamily(Set.of(owner), target, simpleCandidates));
    }

    if (!typeIndex.isReactorType(owner)) {
      return onePhase(simpleCandidates);
    }

    final Set<String> family =
        Stream.concat(Stream.of(owner), subtypeBinaryNames(owner))
            .collect(Collectors.toUnmodifiableSet());
    final Predicate<String> inModule = uri -> isUnder(LatheUri.toPath(uri), config.searchRoots());
    final Set<String> primary =
        Stream.concat(familyFiles(family).stream(), staticImportSites(target))
            .filter(inModule)
            .collect(Collectors.toUnmodifiableSet());
    final Set<String> moduleCandidates =
        simpleCandidates.stream().filter(inModule).collect(Collectors.toUnmodifiableSet());
    // A family named more widely than the method here: the files spelling it are fewer.
    if (primary.size() >= moduleCandidates.size()) {
      return onePhase(simpleCandidates);
    }

    final Set<String> deferred =
        moduleCandidates.stream()
            .filter(uri -> !primary.contains(uri))
            .collect(Collectors.toUnmodifiableSet());
    return new CandidatePlan(primary, deferred, family);
  }

  private static boolean isUnder(final Path path, final List<Path> roots) {
    return roots.stream().anyMatch(path::startsWith);
  }

  private static CandidatePlan onePhase(final Set<String> candidates) {
    return new CandidatePlan(candidates, Set.of(), Set.of());
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

    // Collect all possible import spelling candidates by walking up qualified name prefixes, as
    // source spells them (Outer.Inner); importing an enclosing type also brings a nested one in.
    final var sourceName = qualName.replace('$', '.');
    final Stream.Builder<String> tokensBuilder = Stream.builder();
    tokensBuilder.add(sourceName);
    tokensBuilder.add(sourceName + ".*");

    String current = sourceName;
    while (true) {
      final int dot = current.lastIndexOf('.');
      if (dot <= 0) {
        break;
      }
      current = current.substring(0, dot);
      if (current.length() > targetPkg.length()) {
        tokensBuilder.add(current);
      }

      if (current.indexOf('.') > 0) {
        tokensBuilder.add(current + ".*");
      }
    }
    final Stream<String> importTokens = tokensBuilder.build();

    final List<Path> packageRoots = config.searchRoots();
    return Stream.concat(
            importTokens.flatMap(token -> index.candidateUris(token).stream()),
            simpleCandidates.stream()
                .filter(uri -> isInPackage(LatheUri.toPath(uri), packageRoots, packageRel)))
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
    final Set<String> familyFiles = familyFiles(familyBinaryNames);
    return Stream.concat(
            simpleCandidates.stream().filter(familyFiles::contains), staticImportSites(target))
        .collect(Collectors.toUnmodifiableSet());
  }

  // The files spelling the simple name of a family type.
  private Set<String> familyFiles(final Set<String> familyBinaryNames) {
    return familyBinaryNames.stream()
        .map(ReferenceCandidatePlanner::simpleNameOf)
        .flatMap(name -> index.candidateUris(name).stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  private Stream<String> staticImportSites(final ReferenceTarget target) {
    return Stream.concat(
        index
            .candidateUris("%s.%s".formatted(target.qualifiedName(), target.simpleName()))
            .stream(),
        index.candidateUris(target.qualifiedName() + ".*").stream());
  }

  private static String simpleNameOf(final String binaryName) {
    final int cut = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
    return cut < 0 ? binaryName : binaryName.substring(cut + 1);
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
