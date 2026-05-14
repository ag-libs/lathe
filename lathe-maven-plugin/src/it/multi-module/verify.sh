#!/bin/bash
set -e

fail() { echo "ERROR: $1"; exit 1; }

# --- root marker ---

[ -f .lathe/root.marker ] || fail "root.marker not written by lathe:init"

[ -f .lathe/workspace.properties ] || fail "workspace.properties not written by lathe:sync"

grep -q 'schemaVersion=1' .lathe/workspace.properties \
  || fail "workspace.properties: schemaVersion not written"

grep -q 'dependencySource\.[0-9]*\.jar=' .lathe/workspace.properties \
  || fail "workspace.properties: dependency source jar not written"

grep -q 'dependencySource\.[0-9]*\.gav=' .lathe/workspace.properties \
  || fail "workspace.properties: dependency source gav not written"

grep -q 'dependencySource\.[0-9]*\.status=present' .lathe/workspace.properties \
  || fail "workspace.properties: present dependency source status not written"

grep -q 'dependencySource\.[0-9]*\.dir=' .lathe/workspace.properties \
  || fail "workspace.properties: dependency source dir not written"

grep -Fq 'gav=org.junit.jupiter\:junit-jupiter-api\:5.14.4' .lathe/workspace.properties \
  || fail "workspace.properties: direct test dependency source entry not written"

grep -Fq 'gav=org.opentest4j\:opentest4j\:1.3.0' .lathe/workspace.properties \
  || fail "workspace.properties: transitive test dependency source entry not written"

grep -q 'dependencySource\.[0-9]*\.classpath\.[0-9]*=.*junit-jupiter-api' .lathe/workspace.properties \
  || fail "workspace.properties: dependency source classpath entries not written"

! grep -q 'dependencySource\.[0-9]*\.sources=' .lathe/workspace.properties \
  || fail "workspace.properties: old dependency source sources key still written"

! grep -q 'dependencySource\.[0-9]*\.sourceJar=' .lathe/workspace.properties \
  || fail "workspace.properties: old dependency source sourceJar key still written"

# --- JDK sources ---

grep -Fq 'jdk.vendor=' .lathe/workspace.properties \
  || fail "workspace.properties: jdk.vendor not written"

grep -Fq 'jdk.version=' .lathe/workspace.properties \
  || fail "workspace.properties: jdk.version not written"

grep -Fq 'jdk.sourceStatus=' .lathe/workspace.properties \
  || fail "workspace.properties: jdk.sourceStatus not written"

if grep -Fq 'jdk.sourceStatus=present' .lathe/workspace.properties; then
  grep -Fq 'jdk.sourceDir=' .lathe/workspace.properties \
    || fail "workspace.properties: jdk.sourceDir not written when sourceStatus=present"
  jdk_source_dir=$(grep -F 'jdk.sourceDir=' .lathe/workspace.properties | cut -d= -f2-)
  [ -d "$jdk_source_dir" ] \
    || fail "jdk source dir does not exist: $jdk_source_dir"
fi

# --- core module ---

[ -f .lathe/core/lsp-params-classes.properties ] || fail "core: lsp-params-classes.properties not written"

# --- app module: cross-module dep + annotation processing ---

[ -f .lathe/app/lsp-params-classes.properties ] || fail "app: lsp-params-classes.properties not written"

grep -q "record-companion-builder" .lathe/app/lsp-params-classes.properties \
  || fail "app: processorPath does not contain record-companion-builder"

[ -f ".lathe/app/generated-sources/com/example/app/UserBuilder.java" ] \
  || fail "app: UserBuilder.java not found in generated-sources"

[ -f ".lathe/app/generated-sources/com/example/app/UserUpdater.java" ] \
  || fail "app: UserUpdater.java not found in generated-sources"

# --- jpms module: JPMS + test compilation ---

[ -f .lathe/jpms/lsp-params-classes.properties ] || fail "jpms: lsp-params-classes.properties not written"

grep -q 'Xlint' .lathe/jpms/lsp-params-classes.properties \
  || fail "jpms: -Xlint not found in compilerArgs"

grep -q 'module-version' .lathe/jpms/lsp-params-classes.properties \
  || fail "jpms: --module-version not found in compilerArgs"

grep -q 'validcheck' .lathe/jpms/lsp-params-classes.properties \
  || fail "jpms: validcheck not found in modulepath"

[ -f .lathe/jpms/lsp-params-test-classes.properties ] || fail "jpms: lsp-params-test-classes.properties not written"

grep -q 'sourceTree=test-classes' .lathe/jpms/lsp-params-test-classes.properties \
  || fail "jpms: sourceTree is not test-classes"

grep -q 'src/test/java' .lathe/jpms/lsp-params-test-classes.properties \
  || fail "jpms: test source root not found in sourceRoots"

grep -q 'validcheck' .lathe/jpms/lsp-params-test-classes.properties \
  || fail "jpms: validcheck not found in test modulepath"

grep -q 'junit-jupiter-api' .lathe/jpms/lsp-params-test-classes.properties \
  || fail "jpms: junit-jupiter-api not found in test classpath"

grep -q 'patch-module' .lathe/jpms/lsp-params-test-classes.properties \
  || fail "jpms: --patch-module not found in compilerArgs"

grep -q 'add-reads' .lathe/jpms/lsp-params-test-classes.properties \
  || fail "jpms: --add-reads not found in compilerArgs"

grep -A1 'add-reads' .lathe/jpms/lsp-params-test-classes.properties | grep -q 'com.example.*ALL-UNNAMED' \
  || fail "jpms: com.example.jpms=ALL-UNNAMED not found after --add-reads"

echo "multi-module: OK"
