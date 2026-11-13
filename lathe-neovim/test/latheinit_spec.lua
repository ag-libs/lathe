-- Verifies lathe.latheinit: the build root it finds from a folder (an OpenJDK checkout's markers,
-- Maven's markers, the topmost of a run of pom.xml folders, Gradle's settings script, the nearer of
-- a Maven and a Gradle root, a forced kind), the root prompt (accepted, edited, cancelled), whether
-- the build registers Lathe, and the commands :LatheInit runs: the Maven fetch then the build with
-- the activation, the Gradle build with the init script written for the version, the OpenJDK goal
-- on a built JDK, a plain build for a build that registers Lathe, the attach after a build that
-- wrote a launcher, and the messages for an unbuilt JDK, a development version, no build, a
-- workspace set up already, and a build that wrote no .lathe/. Stubs vim.system, so no build runs.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/latheinit_spec.lua

local spec = require("spec_helper").new()

local calls = {}
local pending_cb
vim.system = function(cmd, opts, on_exit)
  table.insert(calls, { cmd = cmd, opts = opts })
  pending_cb = on_exit
  return { pid = 1 }
end
local notes = {}
vim.notify = function(msg)
  table.insert(notes, msg)
end
vim.schedule = function(fn)
  fn()
end
vim.fn.exepath = function(name)
  return "/opt/bin/" .. name
end
-- The ftplugin a loaded Java buffer runs calls warn_if_not_ready too.
local started = {}
package.loaded["lathe"] = {
  start = function(buf)
    table.insert(started, buf)
  end,
  warn_if_not_ready = function() end,
}
-- The root prompt: accepts the default unless the next answer is set (false cancels).
local prompts = {}
local next_answer
vim.ui.input = function(opts, on_confirm)
  table.insert(prompts, opts)
  local answer = next_answer
  next_answer = nil
  if answer == false then
    on_confirm(nil)
    return
  end
  on_confirm(answer or opts.default)
end

local work = vim.fn.resolve(vim.fn.tempname())
local function write(path, contents)
  vim.fn.mkdir(vim.fs.dirname(path), "p")
  vim.fn.writefile(vim.split(contents or "", "\n"), path)
end
local function executable(path)
  write(path, "#!/bin/sh\n")
  vim.fn.setfperm(path, "rwxr-xr-x")
end
local function finish_build(code)
  local cb = pending_cb
  pending_cb = nil
  cb({ code = code, stdout = "", stderr = "" })
end
local function last_cmd()
  return calls[#calls].cmd
end
local function last_note_has(text)
  return notes[#notes] ~= nil and notes[#notes]:find(text, 1, true) ~= nil
end

vim.env.LATHE_CACHE = work .. "/cache"
vim.env.LATHE_VERSION = "9.9.9"
local latheinit = require("lathe.latheinit")

-- :LatheInit from a folder, with no file open: the folder is where it starts looking.
local function run_from(dir, kind)
  vim.cmd("enew")
  vim.cmd.cd(work .. dir)
  latheinit.run(kind)
end

-- Root finding.
write(work .. "/multi/pom.xml")
write(work .. "/multi/core/pom.xml")
write(work .. "/marked/pom.xml")
write(work .. "/marked/app/pom.xml")
vim.fn.mkdir(work .. "/marked/app/.mvn", "p")
write(work .. "/chain/pom.xml")
write(work .. "/chain/gap/module/pom.xml")
write(work .. "/gradle/settings.gradle.kts")
write(work .. "/gradle/lib/build.gradle.kts")
write(work .. "/mixed/settings.gradle")
write(work .. "/mixed/tools/pom.xml")
write(work .. "/team/pom.xml", "<extension><artifactId>lathe-maven-extension</artifactId>")
write(work .. "/core-ext/pom.xml")
write(work .. "/core-ext/.mvn/extensions.xml", "<artifactId>lathe-maven-extension</artifactId>")
write(work .. "/team-gradle/settings.gradle", 'plugins { id("io.github.ag-libs.lathe") }')
vim.fn.mkdir(work .. "/nothing/src", "p")
for _, jdk in ipairs({ "/jdk", "/jdk-unbuilt", "/jdk-unconfigured", "/jdk-two" }) do
  write(work .. jdk .. "/configure")
  vim.fn.mkdir(work .. jdk .. "/make", "p")
  vim.fn.mkdir(work .. jdk .. "/src/java.base/share/classes", "p")
end
write(work .. "/jdk/build/linux-x64/spec.gmk")
vim.fn.mkdir(work .. "/jdk/build/linux-x64/jdk", "p")
write(work .. "/jdk-unbuilt/build/linux-x64/spec.gmk")
write(work .. "/jdk-two/build/a/spec.gmk")
write(work .. "/jdk-two/build/b/spec.gmk")

local function root_of(dir, kind)
  local found = latheinit.find_build(work .. dir, kind)
  return found and (found.root:sub(#work + 1) .. " " .. found.kind) or "none"
end
spec.check("a module finds the topmost pom.xml", root_of("/multi/core"), "/multi maven")
spec.check(
  "Maven's .mvn/ marks the root under an unrelated pom",
  root_of("/marked/app/src"),
  "/marked/app maven"
)
spec.check(
  "a folder without pom.xml ends the run",
  root_of("/chain/gap/module"),
  "/chain/gap/module maven"
)
spec.check("a subproject finds the settings script", root_of("/gradle/lib"), "/gradle gradle")
spec.check("the nearer of Gradle and Maven wins", root_of("/mixed/tools"), "/mixed/tools maven")
spec.check("a forced kind finds its own root", root_of("/mixed/tools", "gradle"), "/mixed gradle")
spec.check(
  "an OpenJDK checkout is found from a module",
  root_of("/jdk/src/java.base/share/classes"),
  "/jdk openjdk"
)
spec.check("no build found", root_of("/nothing/src"), "none")
spec.check(
  "a plain Maven build does not register Lathe",
  latheinit.find_build(work .. "/multi").registered,
  false
)
spec.check("the POM registers Lathe", latheinit.find_build(work .. "/team").registered, true)
spec.check(
  ".mvn/extensions.xml registers Lathe",
  latheinit.find_build(work .. "/core-ext").registered,
  true
)
spec.check(
  "the settings script registers Lathe",
  latheinit.find_build(work .. "/team-gradle").registered,
  true
)

-- Maven personal: fetch, then build with the activation, then attach.
local boot = work .. "/cache/bootstrap/9.9.9"
run_from("/multi/core")
spec.check(
  "the prompt names the kind",
  prompts[#prompts].prompt,
  "Lathe: set up the Maven build in: "
)
spec.check("the prompt offers the root it found", prompts[#prompts].default, work .. "/multi")
spec.check("Maven fetches first, with the Maven on PATH", last_cmd()[1], "/opt/bin/mvn")
spec.check(
  "the fetch copies the bootstrap POM's runtime dependencies",
  table.concat(last_cmd(), " "):find(
    ("-f %s/pom.xml org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies -DincludeScope=runtime -DoutputDirectory=%s/lib"):format(
      boot,
      boot
    ),
    1,
    true
  ) ~= nil,
  true
)
spec.check(
  "the bootstrap POM depends on the extension at the version",
  table.concat(vim.fn.readfile(boot .. "/pom.xml"), "\n"):find("<version>9.9.9</version>", 1, true)
    ~= nil,
  true
)
spec.check(
  "the fetch fills a temporary folder",
  last_cmd()[#last_cmd()],
  "-DoutputDirectory=" .. boot .. "/lib.fetching"
)
write(boot .. "/lib.fetching/lathe-maven-extension-9.9.9.jar")
write(boot .. "/lib.fetching/lathe-core-9.9.9.jar")
finish_build(0)
spec.check("a finished fetch is renamed into place", vim.uv.fs_stat(boot .. "/lib.fetching"), nil)
spec.check(
  "then the build, Lathe on the ext class path and compile errors kept",
  table.concat(last_cmd(), " "),
  ("/opt/bin/mvn -B -Dmaven.ext.class.path=%s/lib/lathe-core-9.9.9.jar:%s/lib/lathe-maven-extension-9.9.9.jar -Dmaven.compiler.failOnError=false process-test-classes"):format(
    boot,
    boot
  )
)
spec.check("the build runs at the root", calls[#calls].opts.cwd, work .. "/multi")

local java = vim.fn.bufadd(work .. "/multi/core/src/Foo.java")
vim.fn.bufload(java)
vim.bo[java].filetype = "java"
executable(work .. "/multi/.lathe/lathe-launcher.sh")
finish_build(0)
spec.check("a build that wrote a launcher attaches the root's Java buffers", started[1], java)

-- A fetch interrupted before it ended left no lib/: the next run fetches again.
vim.fn.mkdir(work .. "/cache/bootstrap/8.8.8/lib.fetching", "p")
vim.env.LATHE_VERSION = "8.8.8"
run_from("/multi")
spec.check(
  "an interrupted fetch is fetched again",
  last_cmd()[#last_cmd()],
  "-DoutputDirectory=" .. work .. "/cache/bootstrap/8.8.8/lib.fetching"
)
finish_build(1)
spec.check(
  "a failed fetch builds nothing",
  last_cmd()[#last_cmd()]:find("lib.fetching", 1, true) ~= nil,
  true
)

-- A snapshot is fetched every time, for its newest build.
vim.fn.mkdir(work .. "/cache/bootstrap/1.0-SNAPSHOT/lib", "p")
vim.env.LATHE_VERSION = "1.0-SNAPSHOT"
run_from("/multi")
spec.check(
  "a snapshot is fetched again",
  last_cmd()[#last_cmd()],
  "-DoutputDirectory=" .. work .. "/cache/bootstrap/1.0-SNAPSHOT/lib.fetching"
)
finish_build(1)
vim.env.LATHE_VERSION = "9.9.9"

local before = #calls
run_from("/multi")
spec.check("with the jars already fetched, it builds at once", #calls, before + 1)
spec.check("that call is the build", last_cmd()[#last_cmd()], "process-test-classes")
finish_build(0)

-- The root prompt: an edited root is taken as typed; a cancel runs nothing.
next_answer = work .. "/multi/core/"
run_from("/multi/core")
spec.check("an edited root builds there", calls[#calls].opts.cwd, work .. "/multi/core")
finish_build(0)

before = #calls
next_answer = false
run_from("/multi/core")
spec.check("a cancelled prompt runs nothing", #calls, before)

next_answer = work .. "/nothing"
run_from("/multi/core")
spec.check("an edited root without a build runs nothing", #calls, before)
spec.check(
  "and says so",
  last_note_has("no Maven, Gradle or OpenJDK build at " .. work .. "/nothing"),
  true
)

-- A wrapper wins over the tool on PATH.
executable(work .. "/marked/app/mvnw")
run_from("/marked/app")
spec.check("the project's mvnw runs the build", last_cmd()[1], work .. "/marked/app/mvnw")
finish_build(0)
spec.check(
  "a build that wrote no launcher says where to look",
  last_note_has("no `.lathe/` was written at " .. work .. "/marked/app"),
  true
)

-- Gradle personal: the init script, written for the version.
run_from("/gradle/lib")
spec.check(
  "Gradle builds with the init script",
  table.concat(last_cmd(), " "),
  "/opt/bin/gradle --init-script " .. boot .. "/lathe.init.gradle.kts latheSync"
)
local init_script = table.concat(vim.fn.readfile(boot .. "/lathe.init.gradle.kts"), "\n")
spec.check(
  "the init script names the plugin at the version",
  init_script:find('classpath("io.github.ag-libs:lathe-gradle-plugin:9.9.9")', 1, true) ~= nil
    and init_script:find("@VERSION@", 1, true) == nil,
  true
)
finish_build(0)

-- A forced kind runs that kind's build.
run_from("/mixed/tools", "gradle")
spec.check("a forced kind builds at its own root", calls[#calls].opts.cwd, work .. "/mixed")
spec.check("with its own tool", last_cmd()[1], "/opt/bin/gradle")
finish_build(0)
before = #calls
latheinit.run("ant")
spec.check("an unknown kind runs nothing", #calls, before)

-- A build that registers Lathe just builds.
local team_java = vim.fn.bufadd(work .. "/team/src/Team.java")
vim.fn.bufload(team_java)
vim.bo[team_java].filetype = "java"
run_from("/team")
spec.check(
  "a registered Maven build is a plain build",
  table.concat(last_cmd(), " "),
  "/opt/bin/mvn -B process-test-classes"
)
executable(work .. "/team/.lathe/lathe-launcher.sh")
finish_build(1)
spec.check("a failed build that wrote a launcher attaches", started[#started], team_java)
run_from("/team-gradle")
spec.check(
  "a registered Gradle build is a plain build",
  table.concat(last_cmd(), " "),
  "/opt/bin/gradle latheSync"
)
finish_build(0)

-- OpenJDK: a configured and built JDK, then Lathe's goal at the version.
run_from("/jdk/src/java.base")
spec.check(
  "OpenJDK runs Lathe's goal at the version",
  table.concat(last_cmd(), " "),
  "/opt/bin/mvn -B io.github.ag-libs:lathe-openjdk-maven-plugin:9.9.9:sync"
)
spec.check("at the checkout's root", calls[#calls].opts.cwd, work .. "/jdk")
finish_build(0)

before = #calls
run_from("/jdk-unbuilt")
spec.check("an unbuilt JDK says to build it", last_note_has("make jdk"), true)
run_from("/jdk-unconfigured")
spec.check("an unconfigured JDK says to configure it", last_note_has("bash configure"), true)
run_from("/jdk-two")
spec.check(
  "several JDK builds name them and the flag",
  last_note_has("a, b") and last_note_has("-Dlathe.buildDir"),
  true
)
spec.check("none of them runs anything", #calls, before)

-- :LatheInit never syncs: a workspace with no build names :LatheSync.
write(work .. "/synced/.lathe/workspace.json")
run_from("/synced")
spec.check("a .lathe/ without a build runs nothing", #calls, before)
spec.check("and names :LatheSync", last_note_has(":LatheSync"), true)

run_from("/nothing/src")
spec.check("no build and no .lathe/: nothing runs", #calls, before)
spec.check(
  "no build and no .lathe/: says what it looked for",
  last_note_has("no Maven, Gradle or OpenJDK build found"),
  true
)

-- A root typed through a symlink is the same folder: the build runs, and its buffers attach.
vim.uv.fs_symlink(work .. "/multi", work .. "/link")
local attached_before = #started
next_answer = work .. "/link"
run_from("/multi/core")
spec.check("a symlinked root builds at the real folder", calls[#calls].opts.cwd, work .. "/multi")
finish_build(0)
spec.check("and attaches the buffers under it", #started, attached_before + 1)

-- From a buffer that is not a file (a terminal, the sync output), :pwd is where it looks.
vim.cmd("enew")
vim.bo.buftype = "nofile"
vim.api.nvim_buf_set_name(0, "term://fake//1:sh")
vim.cmd.cd(work .. "/gradle")
latheinit.run()
spec.check("a non-file buffer starts at :pwd", calls[#calls].opts.cwd, work .. "/gradle")
finish_build(0)

-- An install missing the init-script template says so instead of failing.
local get_runtime_file = vim.api.nvim_get_runtime_file
vim.api.nvim_get_runtime_file = function()
  return {}
end
before = #calls
run_from("/gradle")
vim.api.nvim_get_runtime_file = get_runtime_file
spec.check("a missing template runs nothing", #calls, before)
spec.check("and says to reinstall", last_note_has("reinstall the plugin"), true)

-- A development client sets up nothing without LATHE_VERSION.
vim.env.LATHE_VERSION = nil
run_from("/gradle")
spec.check("a development version runs nothing", #calls, before)
spec.check("and says to set LATHE_VERSION", last_note_has("LATHE_VERSION"), true)

latheinit.setup()
spec.check("registers :LatheInit", vim.fn.exists(":LatheInit"), 2)
spec.check(
  ":LatheInit completes the build kinds",
  table.concat(vim.fn.getcompletion("LatheInit ", "cmdline"), ","),
  "gradle,maven,openjdk"
)

vim.cmd.cd("/")
vim.fn.delete(work, "rf")
spec.finish()
