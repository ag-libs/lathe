# Lathe — JDK Cache Key Naming

Working design draft.
This document describes a narrower JDK source/type-index cache naming scheme.

---

## 1. Current State

`lathe:sync` extracts JDK sources into the shared user cache.
The current path is built in `JdkSourceResolver` from `java.vendor` and `java.version`:

```text
~/.cache/lathe/jdks/<sanitized-java.vendor>/<sanitized-java.version>/
```

For the current local Corretto runtime,
that becomes:

```text
~/.cache/lathe/jdks/Amazon.com-Inc./26/
```

`JdkTypeIndexSync.indexPath(...)` independently repeats the same vendor/version layout:

```text
~/.cache/lathe/type-index/jdks/<sanitized-java.vendor>/<sanitized-java.version>/index.json
```

This has two issues:

- The source and type-index path logic is duplicated.
- `java.version` is too coarse for exact JDK source caches.
  Corretto `26.0.0.35.2` and a later Corretto `26.0.0.x` release would both map to `26`.

---

## 2. Goal

Use one stable JDK cache key for both extracted JDK sources and the JDK type-index shard:

```text
~/.cache/lathe/jdks/<jdk-cache-key>/
~/.cache/lathe/type-index/jdks/<jdk-cache-key>/index.json
```

For the current local Corretto runtime,
the key should be:

```text
corretto-26.0.0.35.2
```

The source cache path becomes:

```text
~/.cache/lathe/jdks/corretto-26.0.0.35.2/
```

The type-index path becomes:

```text
~/.cache/lathe/type-index/jdks/corretto-26.0.0.35.2/index.json
```

---

## 3. Non-Goals

Do not rename Maven source roots such as `src/main/java` or `src/test/java`.
Lathe should continue using Maven's captured source roots directly.

Do not add a large distro database.
The cache key should rely on metadata exposed by the JDK itself,
with small fallback logic.

Do not migrate or delete existing cache directories.
Old `jdks/<vendor>/<version>` directories can be left in place.

Do not change the workspace manifest schema unless implementation proves it necessary.
The server reads `jdk.sourceDir` and `jdk.typeIndex` directly,
so it does not need to derive the cache path.

---

## 4. Cache Key Source

Prefer the JDK `release` file under `JAVA_HOME`.
This file is present in normal JDK distributions and includes implementation metadata.

Use this precedence:

1. `IMPLEMENTOR_VERSION` from `$JAVA_HOME/release`
2. `IMPLEMENTOR` from `$JAVA_HOME/release` plus `JAVA_VERSION`
3. `java.vendor.version`
4. `java.vendor` plus `java.version`

Release-file entries are checked first so that a synthetic or foreign release file
is not overridden by the running JVM's system properties (relevant in testing).

For example,
this local runtime exposes:

```text
IMPLEMENTOR="Amazon.com Inc."
IMPLEMENTOR_VERSION="Corretto-26.0.0.35.2"
JAVA_VERSION="26"
```

The key should be sanitized and lower-cased:

```text
corretto-26.0.0.35.2
```

For Oracle JDK, `IMPLEMENTOR_VERSION` is absent.
The fallback uses `IMPLEMENTOR` + `JAVA_VERSION`:

```text
IMPLEMENTOR="Oracle Corporation"
JAVA_VERSION="21.0.5"
```

Before sanitizing, strip common legal entity suffixes from the implementor string:

```java
private static final List<String> LEGAL_SUFFIXES =
    List.of(" Corporation", " Corp.", " Corp", " Inc.", " Inc", " Ltd.", " LLC");

private static String stripLegalSuffix(final String implementor) {
  return LEGAL_SUFFIXES.stream()
      .filter(implementor::endsWith)
      .findFirst()
      .map(s -> implementor.substring(0, implementor.length() - s.length()).trim())
      .orElse(implementor);
}
```

This produces `"Oracle"` → `"oracle-21.0.5"` rather than `"oracle-corporation-21.0.5"`.
The list is short and targeted — not a full vendor alias table.
Corretto and Temurin never reach this path because `IMPLEMENTOR_VERSION` is set for both.

If only implementor and Java version are available and no suffix matches,
the fallback key may be less pretty but still stable enough:

```text
amazon.com-inc.-26
```

---

## 5. Implementation Shape

### Jdk cache key helper

Add a focused helper in the Maven plugin JDK package,
for example `JdkCacheKey`.

Responsibilities:

- read `$JAVA_HOME/release` when present
- parse simple `KEY="VALUE"` lines
- choose the best key using the precedence above
- sanitize through `LatheLayout.cacheName(...)`
- lower-case with `Locale.ROOT`

Keep this helper small.
It should not try to understand every possible JDK vendor.

### JdkSource carries the key

Add a plugin-local `cacheKey` field to `JdkSource`.
`JdkSourceResolver` computes it once and passes it into `JdkSource.present(...)` or `JdkSource.missing(...)`.

This avoids recomputing the key in multiple places.
It also lets the type-index path use the same key even when `src.zip` is missing but `JAVA_HOME` is available.

Do not add `cacheKey` to `JdkSourceData` at first.
The manifest already records the final `sourceDir` and `typeIndex` paths,
and those are the only values the server needs for source lookup.

### JDK source extraction path

Change `JdkSourceResolver` from:

```java
LatheLayout.userCacheRoot()
    .resolve(LatheLayout.CACHE_JDKS_DIR)
    .resolve(LatheLayout.cacheName(vendor))
    .resolve(LatheLayout.cacheName(version));
```

to:

```java
LatheLayout.userCacheRoot()
    .resolve(LatheLayout.CACHE_JDKS_DIR)
    .resolve(cacheKey);
```

### JDK type-index path

Change `JdkTypeIndexSync.indexPath(...)` from independently rebuilding the vendor/version path to:

```java
LatheLayout.userCacheRoot()
    .resolve(LatheLayout.TYPE_INDEX_DIR)
    .resolve(LatheLayout.CACHE_JDKS_DIR)
    .resolve(source.cacheKey())
    .resolve(TypeIndexFiles.INDEX_JSON);
```

This is the main DRY improvement.
Both source extraction and type indexing use one cache key.

---

## 6. KISS Notes

Avoid a full vendor alias table.
`IMPLEMENTOR_VERSION` already gives a good key for the important cases:

```text
Corretto-26.0.0.35.2
Temurin-21.0.5+11
```

For Oracle JDK (where `IMPLEMENTOR_VERSION` is absent),
stripping common legal suffixes (`" Corporation"`, `" Inc."`, etc.) from `IMPLEMENTOR`
before building the fallback key keeps it clean: `oracle-21.0.5`.
This is a short fixed list, not a vendor database.

Do not parse `JAVA_HOME` directory names.
In the current local shell,
`JAVA_HOME` is `/opt/jdk`,
while the runtime `java.home` is `/opt/amazon-corretto-26.0.0.35.2-linux-x64`.
The directory name is therefore not a reliable distro source.

---

## 7. Tests

Add focused tests for `JdkCacheKey`:

- `IMPLEMENTOR_VERSION="Corretto-26.0.0.35.2"` produces `corretto-26.0.0.35.2`
- quoted values are unquoted
- spaces and unsupported path characters are sanitized
- missing release file falls back to supplied Java properties

Update `JdkSourceResolverTest`:

- source dir is `jdks/corretto-26.0.0.35.2` for a synthetic release file
- no `src.zip` still returns a missing source with the same cache key

Update `JdkTypeIndexSync` tests or add one:

- JDK type-index path uses the same `source.cacheKey()`

Update manifest writer tests only for expected path strings.
The manifest schema should remain unchanged.

---

## 8. Documentation Updates

Update examples in:

- `docs/lathe-design.md`
- `docs/planned/lathe-source-uri-scheme.md`

Replace examples like:

```text
~/.cache/lathe/jdks/Amazon.com-Inc./26/
```

with:

```text
~/.cache/lathe/jdks/corretto-26.0.0.35.2/
```

---

## 9. Verification

Run:

```bash
mvn spotless:apply -pl lathe-maven-plugin,lathe-server
mvn test -pl lathe-maven-plugin,lathe-server
```

If only Maven plugin tests change,
`mvn test -pl lathe-maven-plugin` is enough for the implementation slice.
