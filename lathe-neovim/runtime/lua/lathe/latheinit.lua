-- :LatheInit [maven|gradle|openjdk]: sets Lathe up for the build around the current file, the root
-- it found confirmed in a prompt. A build that registers Lathe just builds; one that does not gets
-- Lathe from outside, so the project is not changed: Maven through -Dmaven.ext.class.path, Gradle
-- through an init script, both with compile errors kept from stopping the first build. An OpenJDK
-- checkout runs Lathe's OpenJDK goal. It never syncs: :LatheSync refreshes a workspace set up.
-- Design: docs/potential/lathe-init.md.
local M = {}

local workspace = require("lathe.workspace")

local POM = "pom.xml"
local GRADLE_SETTINGS = { "settings.gradle", "settings.gradle.kts" }
local MAVEN_EXTENSION = "lathe-maven-extension"
local GRADLE_PLUGIN_ID = "io.github.ag-libs.lathe"
local DEV_VERSION = "0.0.0-dev"
local COPY_DEPENDENCIES = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies"
local INIT_SCRIPT = "lathe.init.gradle.kts"
local INIT_SCRIPT_TEMPLATE = "templates/" .. INIT_SCRIPT
local OPENJDK_SYNC = "io.github.ag-libs:lathe-openjdk-maven-plugin:%s:sync"
local KINDS = { maven = "Maven", gradle = "Gradle", openjdk = "OpenJDK" }

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

local function registers_lathe(root, kind)
  if kind == "gradle" then
    return read(gradle_settings(root)):find(GRADLE_PLUGIN_ID, 1, true) ~= nil
  end

  if kind == "maven" then
    local files = read(vim.fs.joinpath(root, POM))
      .. read(vim.fs.joinpath(root, ".mvn", "extensions.xml"))
    return files:find(MAVEN_EXTENSION, 1, true) ~= nil
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

local function write_init_script(dir, version)
  local template = vim.api.nvim_get_runtime_file(INIT_SCRIPT_TEMPLATE, false)[1]
  if not template then
    notify(
      ("this install of lathe.nvim has no `%s`: reinstall the plugin."):format(INIT_SCRIPT_TEMPLATE),
      vim.log.levels.ERROR
    )
    return nil
  end

  local script = vim.fs.joinpath(dir, INIT_SCRIPT)
  vim.fn.mkdir(dir, "p")
  vim.fn.writefile(
    vim.split(read(template):gsub("@VERSION@", version), "\n", { plain = true }),
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

-- A build that failed may still have written the launcher (a compile error in a build that
-- registers Lathe): attach, and the server reports the errors. One that succeeded without a
-- launcher ran where Lathe set nothing up: most often a module, as Lathe sets up a build only from
-- its root, or a build tool too old to load Lathe.
local function attach(root, result)
  if vim.fn.executable(workspace.launcher_path(root)) == 1 then
    for _, buf in ipairs(java_buffers_under(root)) do
      require("lathe").start(buf)
    end
    return
  end

  if result.code == 0 then
    notify(
      (
        "no `.lathe/` was written at %s. Is it the build's root (`:LatheInit` asks for it)? Lathe "
        .. "also needs Maven 3.6.3+ or Gradle 8.8+; the build log says why."
      ):format(root),
      vim.log.levels.WARN
    )
  end
end

-- `label` names the build in the toast and the output split: the ext class path is too long for either.
local function build(root, cmd, label)
  require("lathe.sync").run(root, false, nil, {
    cmd = cmd,
    label = label,
    on_done = function(result)
      attach(root, result)
    end,
  })
end

local function maven_personal(build_, tool, version)
  local dir = bootstrap_dir(version)
  local lib = vim.fs.joinpath(dir, "lib")
  local run_build = function()
    local jars = vim.fn.glob(vim.fs.joinpath(lib, "*.jar"), false, true)
    table.sort(jars)
    build(build_.root, {
      tool,
      "-B",
      "-Dmaven.ext.class.path=" .. table.concat(jars, ":"),
      "-Dmaven.compiler.failOnError=false",
      "process-test-classes",
    }, ("%s process-test-classes (Lathe %s)"):format(vim.fs.basename(tool), version))
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
    label = "fetch Lathe " .. version,
    on_done = function(result)
      if result.code ~= 0 then
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
    local cmd = found.kind == "maven" and { tool, "-B", "process-test-classes" }
      or { tool, "latheSync" }
    build(found.root, cmd, table.concat(cmd, " "))
    return
  end

  local version = M.version()
  if not version then
    notify(
      "this is a development client: set LATHE_VERSION to the Lathe version to set up.",
      vim.log.levels.ERROR
    )
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

  local script = write_init_script(bootstrap_dir(version), version)
  if not script then
    return
  end

  build(
    found.root,
    { tool, "--init-script", script, "latheSync" },
    ("%s latheSync (Lathe %s)"):format(vim.fs.basename(tool), version)
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
--- the user confirms or edits its root; an edited root is taken as typed.
function M.run(kind)
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

  vim.ui.input({
    prompt = ("Lathe: set up the %s build in: "):format(KINDS[found.kind]),
    default = found.root,
    completion = "dir",
  }, function(answer)
    if not answer or vim.trim(answer) == "" then
      return
    end

    local typed = vim.fn.fnamemodify(vim.fn.expand(vim.trim(answer)), ":p")
    local root = real(typed)
    local chosen = root and (root == real(found.root) and found or M.build_at(root, kind))
    if not chosen then
      notify(
        ("no %s build at %s."):format(kind and KINDS[kind] or "Maven, Gradle or OpenJDK", typed),
        vim.log.levels.WARN
      )
      return
    end

    set_up(chosen)
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheInit", function(cmd)
    M.run(cmd.args ~= "" and cmd.args or nil)
  end, {
    nargs = "?",
    complete = function()
      local kinds = vim.tbl_keys(KINDS)
      table.sort(kinds)
      return kinds
    end,
    desc = "Lathe: set Lathe up for the build around the current file",
  })
end

return M
