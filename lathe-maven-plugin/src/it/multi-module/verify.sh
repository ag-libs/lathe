#!/bin/bash
set -e

fail() { echo "ERROR: $1"; exit 1; }

# --- init: .lathe/ directory created at initialize phase ---

[ -d .lathe ] || fail ".lathe/ not created by lathe:init"

# --- sync: workspace.json ---

[ -f .lathe/workspace.json ] || fail "workspace.json not written by lathe:sync"

grep -q '"schemaVersion"' .lathe/workspace.json \
  || fail "workspace.json: schemaVersion not written"

grep -q '"workspaceRoot"' .lathe/workspace.json \
  || fail "workspace.json: workspaceRoot not written"

grep -q '"jdk"' .lathe/workspace.json \
  || fail "workspace.json: jdk not written"

grep -q '"dependencySources"' .lathe/workspace.json \
  || fail "workspace.json: dependencySources not written"

grep -q '"org.junit.jupiter:junit-jupiter-api' .lathe/workspace.json \
  || fail "workspace.json: junit-jupiter-api dependency source not written"

grep -q '"org.opentest4j:opentest4j' .lathe/workspace.json \
  || fail "workspace.json: transitive opentest4j dependency source not written"

grep -q '"present"' .lathe/workspace.json \
  || fail "workspace.json: no present dependency source status"

# --- core module ---

[ -f .lathe/core/lsp-params-classes.json ] || fail "core: lsp-params-classes.json not written"

# --- app module: cross-module dep + annotation processing ---

[ -f .lathe/app/lsp-params-classes.json ] || fail "app: lsp-params-classes.json not written"

grep -q '"record-companion-builder"' .lathe/app/lsp-params-classes.json \
  || fail "app: processorPath does not contain record-companion-builder"

[ -f ".lathe/app/generated-sources/com/example/app/UserBuilder.java" ] \
  || fail "app: UserBuilder.java not found in generated-sources"

[ -f ".lathe/app/generated-sources/com/example/app/UserUpdater.java" ] \
  || fail "app: UserUpdater.java not found in generated-sources"

# --- jpms module: JPMS + test compilation ---

[ -f .lathe/jpms/lsp-params-classes.json ] || fail "jpms: lsp-params-classes.json not written"

grep -q 'Xlint' .lathe/jpms/lsp-params-classes.json \
  || fail "jpms: -Xlint not found in compilerArgs"

grep -q 'module-version' .lathe/jpms/lsp-params-classes.json \
  || fail "jpms: --module-version not found in compilerArgs"

grep -q '"validcheck"' .lathe/jpms/lsp-params-classes.json \
  || fail "jpms: validcheck not found in modulepath"

[ -f .lathe/jpms/lsp-params-test-classes.json ] || fail "jpms: lsp-params-test-classes.json not written"

grep -q '"test-classes"' .lathe/jpms/lsp-params-test-classes.json \
  || fail "jpms: sourceTree is not test-classes"

grep -q 'src/test/java' .lathe/jpms/lsp-params-test-classes.json \
  || fail "jpms: test source root not found in sourceRoots"

grep -q '"validcheck"' .lathe/jpms/lsp-params-test-classes.json \
  || fail "jpms: validcheck not found in test modulepath"

grep -q 'junit-jupiter-api' .lathe/jpms/lsp-params-test-classes.json \
  || fail "jpms: junit-jupiter-api not found in test classpath"

grep -q 'patch-module' .lathe/jpms/lsp-params-test-classes.json \
  || fail "jpms: --patch-module not found in compilerArgs"

grep -q 'add-reads' .lathe/jpms/lsp-params-test-classes.json \
  || fail "jpms: --add-reads not found in compilerArgs"

grep -q 'ALL-UNNAMED' .lathe/jpms/lsp-params-test-classes.json \
  || fail "jpms: ALL-UNNAMED not found in --add-reads arg"

echo "multi-module: OK"
