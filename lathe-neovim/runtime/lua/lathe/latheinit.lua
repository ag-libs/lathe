-- :LatheInit [maven|gradle|openjdk]: sets Lathe up for the build around the current file, the root
-- it found confirmed in a prompt. A build that registers Lathe builds as it is; one that does not
-- gets Lathe from outside, so the project is not changed: Maven through -Dmaven.ext.class.path,
-- Gradle through an init script. Either way the first build is the documented first run, with
-- compile errors kept from stopping it. An OpenJDK checkout runs Lathe's OpenJDK goal. It never
-- syncs: :LatheSync refreshes a workspace set up, so a root that a build would leave as it is gets
-- a message naming it instead; :LatheInit! builds it anyway.
-- Design: docs/potential/lathe-init.md.
local M = {}

local workspace = require("lathe.workspace")

local POM = "pom.xml"
local GRADLE_SETTINGS = { "settings.gradle", "settings.gradle.kts" }
local MAVEN_EXTENSION = "lathe-maven-extension"
-- The extension, or the plugin a build wires by hand with the compiler shim (the Maven guide's
-- manual configuration).
local MAVEN_REGISTRATIONS = {
  "<artifactId>%s*lathe%-maven%-extension%s*</artifactId>",
  "<artifactId>%s*lathe%-maven%-plugin%s*</artifactId>",
}
local GRADLE_PLUGIN_ID = "io.github.ag-libs.lathe"
local DEV_VERSION = "0.0.0-dev"
local COPY_DEPENDENCIES = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies"
local INIT_SCRIPT = "lathe.init.gradle.kts"
local FAIL_ON_ERROR_SCRIPT = "lathe.failonerror.gradle.kts"
local OPENJDK_SYNC = "io.github.ag-libs:lathe-openjdk-maven-plugin:%s:sync"
local KINDS = { maven = "Maven", gradle = "Gradle", openjdk = "OpenJDK" }
-- The first build: clean, as Maven compiles nothing it finds up to date and Lathe captures a module
-- only as it compiles it; the tests captured without running; compile errors kept from stopping
-- it. Gradle reruns a compile whose inputs changed, so it needs no clean.
local MAVEN_FIRST_BUILD = {
  "clean",
  "test",
  "-Dlathe.capture.only=true",
  "-DfailIfNoTests=false",
  "-Dmaven.compiler.failOnError=false",
}
local GRADLE_FIRST_BUILD = { "latheSync", "-Plathe.capture.only=true", "--continue" }

local function exists(path)
  return vim.uv.fs_stat(path) ~= nil
end

local function read(path)
  return exists(path) and table.concat(vim.fn.readfile(path), "\n") or ""
end

local function notify(msg, level)
  vim.notify("Lathe: " .. msg, level or vim.log.levels.INFO, { title = "Lathe" })
end

-- dir and every folder above it, nearest first.
local function upward(dir)
  local dirs = { dir }
  for parent in vim.fs.parents(dir) do
    table.insert(dirs, parent)
  end
  return dirs
end

local function first(dirs, accept)
  for _, dir in ipairs(dirs) do
    if accept(dir) then
      return dir
    end
  end
  return nil
end

local function has_pom(dir)
  return exists(vim.fs.joinpath(dir, POM))
end

-- Maven's own root markers beside a pom.xml; without them, the topmost folder of the unbroken run of
-- folders holding one, the usual multi-module layout. A guess: Lathe's build-side check reads each
-- parent's <modules>, and skips a module build, which leaves no .lathe/ behind.
local function maven_root(dirs)
  local marked = first(dirs, function(dir)
    return has_pom(dir)
      and (exists(vim.fs.joinpath(dir, ".mvn")) or exists(vim.fs.joinpath(dir, "mvnw")))
  end)
  if marked then
    return marked
  end

  local top = nil
  for _, dir in ipairs(dirs) do
    if has_pom(dir) then
      top = dir
    elseif top then
      break
    end
  end
  return top
end

local function gradle_settings(dir)
  return first(
    vim.tbl_map(function(name)
      return vim.fs.joinpath(dir, name)
    end, GRADLE_SETTINGS),
    exists
  )
end

-- Gradle's own rule: the nearest settings script marks the build's root.
local function gradle_root(dirs)
  return first(dirs, function(dir)
    return gradle_settings(dir) ~= nil
  end)
end

-- An OpenJDK checkout's own layout, at its root.
local function is_openjdk(dir)
  return exists(vim.fs.joinpath(dir, "configure"))
    and exists(vim.fs.joinpath(dir, "make"))
    and exists(vim.fs.joinpath(dir, "src", "java.base"))
end

local ROOTS = {
  openjdk = function(dirs)
    return first(dirs, is_openjdk)
  end,
  gradle = gradle_root,
  maven = maven_root,
}

-- Whether the build at root has kind's own files there, for a root the user typed.
local AT = {
  openjdk = is_openjdk,
  gradle = function(dir)
    return gradle_settings(dir) ~= nil
  end,
  maven = has_pom,
}

-- Lathe named in the build's own files, comments left out: a registration commented out loads
-- nothing. A text check: a parent POM that registers Lathe is not seen, and the build's own Lathe
-- then wins over the one :LatheInit brings in.
local function registers_lathe(root, kind)
  if kind == "gradle" then
    local script = ("\n" .. read(gradle_settings(root)))
      :gsub("/%*.-%*/", "")
      :gsub("\n%s*//[^\n]*", "")
    return script:find(('"%s"'):format(GRADLE_PLUGIN_ID), 1, true) ~= nil
      or script:find(("'%s'"):format(GRADLE_PLUGIN_ID), 1, true) ~= nil
  end

  if kind == "maven" then
    local files = read(vim.fs.joinpath(root, POM))
      .. read(vim.fs.joinpath(root, ".mvn", "extensions.xml"))
    local uncommented = files:gsub("<!%-%-.-%-%->", "")
    return vim.iter(MAVEN_REGISTRATIONS):any(function(registration)
      return uncommented:find(registration) ~= nil
    end)
  end

  return false
end

local function describe(root, kind)
  return { root = root, kind = kind, registered = registers_lathe(root, kind) }
end

--- The build around `start` (a folder): `{ root, kind = "maven"|"gradle"|"openjdk", registered }`,
--- or nil. With `kind`, only that kind's root. Otherwise an OpenJDK checkout first, then the nearer
--- of a Maven and a Gradle root; at the same folder, Gradle, whose settings script is the more
--- explicit marker.
function M.find_build(start, kind)
  local dirs = upward(start)
  if kind then
    local root = ROOTS[kind](dirs)
    return root and describe(root, kind) or nil
  end

  local jdk = ROOTS.openjdk(dirs)
  if jdk then
    return describe(jdk, "openjdk")
  end

  local gradle, maven = gradle_root(dirs), maven_root(dirs)
  if gradle and (not maven or #gradle >= #maven) then
    return describe(gradle, "gradle")
  end

  return maven and describe(maven, "maven") or nil
end

--- The build at exactly `root`, a folder the user typed: `kind`'s if given, else the first kind
--- whose files are there.
function M.build_at(root, kind)
  for _, candidate in ipairs(kind and { kind } or { "openjdk", "gradle", "maven" }) do
    if AT[candidate](root) then
      return describe(root, candidate)
    end
  end
  return nil
end

-- The project's wrapper, else the tool on PATH by its full path: a build never starts from a bare
-- tool name.
-- An OpenJDK checkout has no wrapper: its goal runs on the Maven on PATH.
local function build_tool(build)
  local gradle = build.kind == "gradle"
  local wrapper = vim.fs.joinpath(build.root, gradle and "gradlew" or "mvnw")
  if vim.fn.executable(wrapper) == 1 then
    return wrapper
  end

  local on_path = vim.fn.exepath(gradle and "gradle" or "mvn")
  return on_path ~= "" and on_path or nil
end

-- The Lathe version to set up: this client's, or LATHE_VERSION for a development checkout, whose
-- version is a placeholder.
function M.version()
  local version = vim.env.LATHE_VERSION or require("lathe.version").VERSION
  return version ~= DEV_VERSION and version or nil
end

local function bootstrap_dir(version)
  return vim.fs.joinpath(workspace.cache_root(), "bootstrap", version)
end

-- The bootstrap POM: lathe-maven-extension alone, so Maven works out the jars it needs, its runtime
-- dependencies included, which -Dmaven.ext.class.path does not resolve.
local function write_bootstrap_pom(dir, version)
  vim.fn.mkdir(dir, "p")
  vim.fn.writefile({
    '<project xmlns="http://maven.apache.org/POM/4.0.0">',
    "  <modelVersion>4.0.0</modelVersion>",
    "  <groupId>local.lathe</groupId>",
    "  <artifactId>bootstrap</artifactId>",
    "  <version>0</version>",
    "  <packaging>pom</packaging>",
    "  <dependencies>",
    "    <dependency>",
    "      <groupId>io.github.ag-libs</groupId>",
    "      <artifactId>" .. MAVEN_EXTENSION .. "</artifactId>",
    "      <version>" .. version .. "</version>",
    "    </dependency>",
    "  </dependencies>",
    "</project>",
  }, vim.fs.joinpath(dir, POM))
end

-- The template `name` written to dir, @VERSION@ replaced when a version is given. The sync script
-- replays the init scripts the build ran with, so it is written where the plugin's install cannot
-- move it.
local function write_init_script(name, dir, version)
  local path = "templates/" .. name
  local template = vim.api.nvim_get_runtime_file(path, false)[1]
  if not template then
    notify(
      ("this install of lathe.nvim has no `%s`: reinstall the plugin."):format(path),
      vim.log.levels.ERROR
    )
    return nil
  end

  local script = vim.fs.joinpath(dir, name)
  vim.fn.mkdir(dir, "p")
  vim.fn.writefile(
    vim.split(read(template):gsub("@VERSION@", version or ""), "\n", { plain = true }),
    script
  )
  return script
end

-- The same folder whatever symlinks a path to it goes through.
local function real(path)
  return path and vim.uv.fs_realpath(path) or nil
end

local function java_buffers_under(root)
  local wanted = real(root)
  return vim.tbl_filter(function(buf)
    return vim.api.nvim_buf_is_loaded(buf)
      and vim.bo[buf].filetype == "java"
      and real(workspace.get_root(buf)) == wanted
  end, vim.api.nvim_list_bufs())
end

-- The Lathe version the build set up, which the workspace records.
local function server_version(root)
  local manifest = workspace.manifest_path(root)
  if not exists(manifest) then
    return nil
  end

  local ok, decoded = pcall(vim.json.decode, read(manifest))
  return ok and type(decoded) == "table" and decoded.serverVersion or nil
end

-- Names the version set up, and says when the build pinned another than the one asked for.
local function report_version(root, running, wanted)
  if not running then
    return
  end

  if wanted and running ~= wanted then
    notify(
      ("server %s set up at %s: the project's build pins it, so it runs instead of %s."):format(
        running,
        root,
        wanted
      )
    )
    return
  end

  notify(("server %s set up at %s."):format(running, root))
end

-- A buffer opened before the setup took the fallback indent; the project's style is there now.
local function start_servers(root)
  for _, buf in ipairs(java_buffers_under(root)) do
    require("lathe.indent").apply_project_indent(buf)
    require("lathe").start(buf)
  end
end

local function servers_at(root)
  local wanted = real(root)
  return vim.tbl_filter(function(client)
    return real(client.root_dir) == wanted
  end, vim.lsp.get_clients({ name = "lathe" }))
end

local STOP_POLL_MS = 100
local STOP_GIVE_UP_AFTER = 100

-- Starts the new servers once the old ones have exited. One that is still running after ten
-- seconds is left alone rather than killed, which would read as a crash.
local function restart_when_stopped(root, clients, polls)
  local running = vim.tbl_filter(function(client)
    return not client:is_stopped()
  end, clients)
  if #running == 0 then
    start_servers(root)
    return
  end

  if polls == STOP_GIVE_UP_AFTER then
    notify(
      ("the server running at %s did not stop: `:lsp restart` starts the one set up."):format(root),
      vim.log.levels.WARN
    )
    return
  end

  vim.defer_fn(function()
    restart_when_stopped(root, running, polls + 1)
  end, STOP_POLL_MS)
end

-- A build that failed may still have written the launcher (a module failed after Lathe set up the
-- rest): attach, and the server serves what was set up. When the build changed the version the
-- workspace records, a server already running there serves the old one: it is restarted. The
-- workspace, not the server, says what changed, as a development server reports no version.
local function attach(root, result, version, before)
  if vim.fn.executable(workspace.launcher_path(root)) == 1 then
    local installed = server_version(root)
    if result.code == 0 then
      report_version(root, installed, version)
    end

    local stale = before and installed ~= before and servers_at(root) or {}
    for _, client in ipairs(stale) do
      client:stop()
    end

    restart_when_stopped(root, stale, 0)
  end
end

-- A build that succeeded but installed no server: one that set up the workspace could not fetch the
-- server (the project's repositories lack it), one that did not ran where Lathe set nothing up.
-- Either way the build's output says why, shown with the warning.
local function no_server(root, started)
  if vim.fn.executable(workspace.launcher_path(root)) == 1 then
    return nil
  end

  local manifest = vim.uv.fs_stat(workspace.manifest_path(root))
  if manifest and manifest.mtime.sec >= started then
    return (
      "Lathe: the build set up `.lathe/` at %s but installed no Lathe server: its `[lathe]` lines "
      .. "in the Lathe Sync Output split say why."
    ):format(root)
  end

  return (
    "Lathe: no `.lathe/` was written at %s. Is it the build's root (`:LatheInit` asks for it)? "
    .. "Lathe also needs Maven 3.6.3+ or Gradle 8.8+; the Lathe Sync Output split shows the build."
  ):format(root)
end

-- `label` names the build in the toast and the output split: the ext class path is too long for either.
-- A build that failed after Lathe set it up (a module that does not build): one warning that says
-- both, rather than the failed setup's error followed by the server set up.
-- Only when this build wrote the workspace: a launcher left by an earlier setup says nothing about
-- a build that failed before Lathe ran.
local function set_up_but_failed(root, result, started)
  local manifest = vim.uv.fs_stat(workspace.manifest_path(root))
  if not manifest or manifest.mtime.sec < started then
    return nil
  end

  local installed = server_version(root)
  return (
    "Lathe: %s set up at %s, but the build failed (exit %d): what failed to build may not be "
    .. "analysed — see the Lathe Sync Output split."
  ):format(installed and ("server %s"):format(installed) or "Lathe", root, result.code)
end

-- `version` is the Lathe brought in from outside, nil for a build that registers its own.
local function build(root, cmd, label, version)
  local before = server_version(root)
  local started = os.time()
  require("lathe.sync").run(root, false, nil, {
    cmd = cmd,
    label = label,
    action = "setup",
    failure_toast = function(result)
      return set_up_but_failed(root, result, started)
    end,
    success_warning = function()
      return no_server(root, started)
    end,
    on_done = function(result)
      attach(root, result, version, before)
    end,
  })
end

local function maven_personal(build_, tool, version)
  local dir = bootstrap_dir(version)
  local lib = vim.fs.joinpath(dir, "lib")
  local run_build = function()
    local jars = vim.fn.glob(vim.fs.joinpath(lib, "*.jar"), false, true)
    table.sort(jars)
    build(
      build_.root,
      vim.list_extend(
        { tool, "-B", "-Dmaven.ext.class.path=" .. table.concat(jars, ":") },
        MAVEN_FIRST_BUILD
      ),
      ("%s clean test (capture only, Lathe %s)"):format(vim.fs.basename(tool), version),
      version
    )
  end
  -- lib/ appears only whole: the fetch fills a temporary folder renamed into place when it ends, so
  -- an interrupted one is fetched again. A snapshot is fetched every time, for its newest build.
  if exists(lib) and not version:find("%-SNAPSHOT$") then
    run_build()
    return
  end

  local fetching = lib .. ".fetching"
  vim.fn.delete(fetching, "rf")
  write_bootstrap_pom(dir, version)
  require("lathe.sync").run(build_.root, false, nil, {
    cmd = {
      tool,
      "-B",
      "-q",
      "-f",
      vim.fs.joinpath(dir, POM),
      COPY_DEPENDENCIES,
      "-DincludeScope=runtime",
      "-DoutputDirectory=" .. fetching,
    },
    label = ("%s copy-dependencies (Lathe %s)"):format(vim.fs.basename(tool), version),
    action = "download",
    on_done = function(result)
      -- A failed fetch leaves no partial download; the bootstrap POM stays for the output
      -- split's retry, which runs the same command.
      if result.code ~= 0 then
        vim.fn.delete(fetching, "rf")
        return
      end

      vim.fn.delete(lib, "rf")
      vim.uv.fs_rename(fetching, lib)
      run_build()
    end,
  })
end

-- Lathe never runs make: the JDK must be configured and built, in exactly one configuration (the
-- OpenJDK goal's own rule; several need -Dlathe.buildDir, which :LatheInit does not choose).
local function openjdk(found, tool, version)
  local specs = vim.fn.glob(vim.fs.joinpath(found.root, "build", "*", "spec.gmk"), false, true)
  local goal = OPENJDK_SYNC:format(version)
  if #specs == 0 then
    notify(
      "configure and build the JDK first: `bash configure` and `make jdk`.",
      vim.log.levels.ERROR
    )
    return
  end

  if #specs > 1 then
    local confs = vim.tbl_map(function(spec)
      return vim.fs.basename(vim.fs.dirname(spec))
    end, specs)
    notify(
      ("several JDK builds (%s): run `mvn %s -Dlathe.buildDir=build/<conf>` for the one to use."):format(
        table.concat(confs, ", "),
        goal
      ),
      vim.log.levels.ERROR
    )
    return
  end

  if not exists(vim.fs.joinpath(vim.fs.dirname(specs[1]), "jdk")) then
    notify("build the JDK first: `make jdk`.", vim.log.levels.ERROR)
    return
  end

  build(found.root, { tool, "-B", goal }, ("mvn %s"):format(goal))
end

-- The version to set up, else nil once it said how a development client names one.
local function version_to_set_up()
  local version = M.version()
  if not version then
    notify(
      "this is a development client: set LATHE_VERSION to the Lathe version to set up.",
      vim.log.levels.ERROR
    )
  end
  return version
end

local function set_up(found)
  local tool = build_tool(found)
  if not tool then
    notify(
      ("no %s found: the project has no wrapper and none is on PATH."):format(
        found.kind == "gradle" and "gradle" or "mvn"
      ),
      vim.log.levels.ERROR
    )
    return
  end

  if found.registered then
    notify(("building %s, which registers Lathe…"):format(found.root))
    if found.kind == "maven" then
      local cmd = vim.list_extend({ tool, "-B" }, MAVEN_FIRST_BUILD)
      build(found.root, cmd, ("%s clean test (capture only)"):format(vim.fs.basename(tool)))
      return
    end

    local script =
      write_init_script(FAIL_ON_ERROR_SCRIPT, vim.fs.joinpath(workspace.cache_root(), "bootstrap"))
    if not script then
      return
    end

    local cmd = vim.list_extend({ tool, "--init-script", script }, GRADLE_FIRST_BUILD)
    build(found.root, cmd, ("%s latheSync"):format(vim.fs.basename(tool)))
    return
  end

  local version = version_to_set_up()
  if not version then
    return
  end

  if found.kind == "openjdk" then
    openjdk(found, tool, version)
    return
  end

  notify(
    ("setting up %s (%s, Lathe %s; your project files are not changed)…"):format(
      found.root,
      KINDS[found.kind],
      version
    )
  )
  if found.kind == "maven" then
    maven_personal(found, tool, version)
    return
  end

  local script = write_init_script(INIT_SCRIPT, bootstrap_dir(version), version)
  if not script then
    return
  end

  build(
    found.root,
    vim.list_extend({ tool, "--init-script", script }, GRADLE_FIRST_BUILD),
    ("%s latheSync (Lathe %s)"):format(vim.fs.basename(tool), version),
    version
  )
end

-- A file buffer's folder; any other buffer (a terminal, the sync output, oil://) starts at :pwd.
local function start_dir()
  local name = vim.api.nvim_buf_get_name(0)
  if vim.bo.buftype ~= "" or name == "" or name:find("://", 1, true) then
    return vim.fn.getcwd()
  end

  return vim.fs.dirname(vim.fs.normalize(name))
end

-- Whether the current file, not :pwd, picked found's root: from :pwd another root, or none, would
-- have been offered.
local function found_from_file(start, found, kind)
  local cwd = vim.fn.getcwd()
  if real(start) == real(cwd) then
    return false
  end

  local from_pwd = M.find_build(cwd, kind)
  return not from_pwd or real(from_pwd.root) ~= real(found.root)
end

-- The file the build's sync script names to bring Lathe `version` in from outside: the activation
-- jars' folder on Maven, the init script on Gradle.
local function activation(kind, version)
  local dir = bootstrap_dir(version)
  return kind == "gradle" and vim.fs.joinpath(dir, INIT_SCRIPT) or vim.fs.joinpath(dir, "lib")
end

-- A sync script that brings Lathe in from outside, as only a personal setup's does.
local function replays_activation(script)
  return script:find("maven.ext.class.path", 1, true) ~= nil
    or script:find(INIT_SCRIPT, 1, true) ~= nil
end

-- Why building found's root would change nothing (a message naming :LatheSync), else nil. A root is
-- set up when its build's sync wrote the manifest and the launcher. Then a build that registers
-- Lathe picks the version itself, unless its script still replays a personal setup's activation,
-- which would override that registration until a build renders a new script. An OpenJDK checkout
-- is set up at this version; a personal build when its script brings this version in for this kind
-- and that file is still there (the script refuses to sync without it). Any other personal script
-- builds: another version, one rendered while the build registered Lathe, the other kind's.
local function set_up_already(found, version)
  local root = found.root
  if
    not exists(workspace.manifest_path(root))
    or not exists(workspace.workspace_launcher_path(root))
  then
    return nil
  end

  local installed = server_version(root)
  local script = read(vim.fs.joinpath(root, workspace.SYNC_SCRIPT))
  local hint = ("`:LatheSync` refreshes it, and `:LatheInit!` %s."):format(
    found.kind == "maven" and "rebuilds it from clean" or "sets it up again"
  )
  if found.registered then
    if replays_activation(script) then
      return nil
    end

    return ("Lathe: %s at %s by the project's build, which registers Lathe: %s"):format(
      installed and ("server %s is set up"):format(installed) or "Lathe is set up",
      root,
      hint
    )
  end

  if not version then
    return nil
  end

  local current = found.kind == "openjdk" and installed == version
    or found.kind ~= "openjdk"
      and script:find(activation(found.kind, version), 1, true) ~= nil
      and exists(activation(found.kind, version))
  return current and ("Lathe: Lathe %s is set up at %s already: %s"):format(version, root, hint)
    or nil
end

-- Whether a run (a sync, or this command's download or build) is going at root: then say so.
local function busy(root)
  if not require("lathe.sync").busy(root) then
    return false
  end

  notify(("a sync or setup is already running at %s."):format(root))
  return true
end

-- What :LatheInit is about to do at the root it found, the version it moves from, and whether the
-- root came from the current file rather than :pwd.
local function prompt_for(found, from_file, again)
  local origin = from_file and " (found from the current file)" or ""
  local repeated = again and " again" or ""
  if found.registered then
    return ("Lathe: build the %s project%s%s, which registers Lathe, in: "):format(
      KINDS[found.kind],
      repeated,
      origin
    )
  end

  local version = M.version()
  local installed = server_version(found.root)
  local from = installed and installed ~= version and (" (now %s)"):format(installed) or ""
  return ("Lathe: set up Lathe %s%s%s for the %s build%s in: "):format(
    version,
    from,
    repeated,
    KINDS[found.kind],
    origin
  )
end

-- A root typed below the one found is most often a module, which Lathe does not set up alone.
local function warn_if_module(chosen, found)
  local inner, outer = real(chosen.root), real(found.root)
  if chosen.kind ~= found.kind or not vim.startswith(inner, outer .. "/") then
    return
  end

  notify(
    (
      "%s is inside the %s build at %s: if it is one of its modules, set up %s instead, as Lathe "
      .. "sets a build up from its root."
    ):format(chosen.root, KINDS[found.kind], found.root, found.root),
    vim.log.levels.WARN
  )
end

local function not_found(start, kind)
  if vim.fs.root(start, workspace.ROOT_MARKER) then
    notify("Lathe is set up here already: `:LatheSync` refreshes it.")
    return
  end

  notify(
    ("no %s build found from %s."):format(kind and KINDS[kind] or "Maven, Gradle or OpenJDK", start),
    vim.log.levels.WARN
  )
end

--- Sets Lathe up for the build around the current file (else `:pwd`), of `kind` if given, after
--- the user confirms or edits its root; an edited root is taken as typed, a relative one from the
--- root offered. A root set up already gets a message naming `:LatheSync` instead, unless `force`.
function M.run(kind, force)
  if kind and not KINDS[kind] then
    notify(
      ("unknown build kind %s: use maven, gradle or openjdk."):format(kind),
      vim.log.levels.ERROR
    )
    return
  end

  local start = start_dir()
  local found = M.find_build(start, kind)
  if not found then
    not_found(start, kind)
    return
  end

  -- A build that registers Lathe picks its own version: a development client builds it too.
  local version = M.version()
  if not found.registered and not version_to_set_up() then
    return
  end

  if busy(found.root) then
    return
  end

  local already = set_up_already(found, version)
  if already and not force then
    vim.notify(already, vim.log.levels.INFO, { title = "Lathe" })
    return
  end

  vim.ui.input({
    prompt = prompt_for(found, found_from_file(start, found, kind), already ~= nil),
    default = found.root,
    completion = "dir",
  }, function(answer)
    if not answer or vim.trim(answer) == "" then
      return
    end

    -- A relative path is taken from the root offered, which the prompt shows.
    local typed = vim.fn.expand(vim.trim(answer))
    if vim.fn.isabsolutepath(typed) == 0 then
      typed = vim.fs.joinpath(found.root, typed)
    end

    local root = real(typed)
    local chosen = root and (root == real(found.root) and found or M.build_at(root, kind))
    if not chosen then
      notify(
        ("no %s build at %s."):format(kind and KINDS[kind] or "Maven, Gradle or OpenJDK", typed),
        vim.log.levels.WARN
      )
      return
    end

    if busy(chosen.root) then
      return
    end

    local chosen_already = chosen ~= found and not force and set_up_already(chosen, version)
    if chosen_already then
      vim.notify(chosen_already, vim.log.levels.INFO, { title = "Lathe" })
      return
    end

    warn_if_module(chosen, found)
    set_up(chosen)
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheInit", function(cmd)
    M.run(cmd.args ~= "" and cmd.args or nil, cmd.bang)
  end, {
    nargs = "?",
    bang = true,
    complete = function()
      local kinds = vim.tbl_keys(KINDS)
      table.sort(kinds)
      return kinds
    end,
    desc = "Lathe: set Lathe up for the build around the current file (! even one set up already)",
  })
end

return M
