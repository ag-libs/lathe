# Lathe — Refactoring & Renaming Plan

Deferred items not yet implemented. See `docs/done/lathe-refactoring-renaming.md` for
everything completed.

---

## Test Package & Module Co-Location Inconsistencies

### `SyncMojoTest` Location
`SyncMojoTest` in `lathe-maven-plugin` actually tests `LatheFlags` (which is in `lathe-core`).
Move this test to `lathe-core` and rename it to `LatheFlagsTest` to align test co-location with
the module under test.

### Package Structure Mismatch
Test classes `DependencySourceTest`, `DependencySourceSyncTest`, and `JdkSourceResolverTest` live
in the root package `io.github.aglibs.lathe.maven` instead of matching their production packages
(`io.github.aglibs.lathe.maven.dependency` and `io.github.aglibs.lathe.maven.jdk`).
Relocate these tests to their corresponding subpackages.
