#!/bin/bash
set -e

fail() { echo "ERROR: $1"; exit 1; }

# --- root marker ---

[ -f .lathe/root.marker ] || fail "root.marker not written by lathe:init"

[ ! -f .lathe/workspace.properties ] || fail "workspace.properties not reset by lathe:init"

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
