-- Verifies lathe.latheinit: the build root it finds from a folder (an OpenJDK checkout's markers,
-- Maven's markers, the topmost of a run of pom.xml folders, Gradle's settings script, the nearer of
-- a Maven and a Gradle root, a forced kind), the root prompt (accepted, edited, cancelled), whether
-- the build registers Lathe, and the commands :LatheInit runs: the Maven fetch then the first build
-- (clean, tests captured, compile errors kept) with the activation, the Gradle build with the init
-- script written for the version, the OpenJDK goal on a built JDK, the same first build for a build
-- that registers Lathe (on Gradle with an init script that keeps compile errors), the attach after
-- a build that wrote a launcher, and the messages for an unbuilt JDK, a development version, no
-- build, a workspace set up already, and a build that wrote no .lathe/. A root a build would leave
-- as it is (registered and set up, personal with a sync script at this version and kind, OpenJDK
-- at this version) runs nothing unless forced; one with a run going asks nothing. Stubs
-- vim.system, so no build runs.
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
-- The running Lathe servers :LatheInit sees; none unless a check sets them.
local clients = {}
vim.lsp.get_clients = function()
  return clients
end
local function server(root, version)
  return {
    root_dir = root,
    server_info = { version = version },
    stop = function(self)
      self.stopped = true
    end,
    is_stopped = function(self)
      return self.stopped == true
    end,
  }
end
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
local function finish_build(code, stdout)
  local cb = pending_cb
  pending_cb = nil
  cb({ code = code, stdout = stdout or "", stderr = "" })
end
local function console_has(text)
  return vim
    .iter(vim.api.nvim_buf_get_lines(vim.fn.bufnr("Lathe Sync Output"), 0, -1, false))
    :any(function(line)
      return line:find(text, 1, true) ~= nil
    end)
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
write(work .. "/manual/pom.xml", "<plugin><artifactId>lathe-maven-plugin</artifactId></plugin>")
write(work .. "/core-ext/.mvn/extensions.xml", "<artifactId>lathe-maven-extension</artifactId>")
write(work .. "/team-gradle/settings.gradle", 'plugins { id("io.github.ag-libs.lathe") }')
write(
  work .. "/commented/pom.xml",
  "<project>\n<!-- later: <artifactId>lathe-maven-extension</artifactId> -->\n</project>"
)
write(
  work .. "/commented-gradle/settings.gradle.kts",
  '// id("io.github.ag-libs.lathe")\n/* id("io.github.ag-libs.lathe") */\nrootProject.name = "x"'
)
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
spec.check(
  "the plugin wired by hand registers Lathe",
  latheinit.find_build(work .. "/manual").registered,
  true
)
spec.check(
  "a registration in an XML comment does not count",
  latheinit.find_build(work .. "/commented").registered,
  false
)
spec.check(
  "a registration in a Gradle comment does not count",
  latheinit.find_build(work .. "/commented-gradle").registered,
  false
)

-- Maven personal: fetch, then build with the activation, then attach.
local boot = work .. "/cache/bootstrap/9.9.9"
run_from("/multi/core")
spec.check(
  "the prompt names the version and the kind",
  prompts[#prompts].prompt,
  "Lathe: set up Lathe 9.9.9 for the Maven build in: "
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
  "then the first build, Lathe on the ext class path",
  table.concat(last_cmd(), " "),
  ("/opt/bin/mvn -B -Dmaven.ext.class.path=%s/lib/lathe-core-9.9.9.jar:%s/lib/lathe-maven-extension-9.9.9.jar -Dlathe.capture.only=true -DfailIfNoTests=false -Dmaven.compiler.failOnError=false clean test"):format(
    boot,
    boot
  )
)
spec.check("the build runs at the root", calls[#calls].opts.cwd, work .. "/multi")

local java = vim.fn.bufadd(work .. "/multi/core/src/Foo.java")
vim.fn.bufload(java)
vim.bo[java].filetype = "java"
executable(work .. "/multi/.lathe/lathe-launcher.sh")
write(work .. "/multi/.lathe/workspace.json", '{"serverVersion": "9.9.9"}')
write(work .. "/multi/.lathe/style.json", '{"indent": {"profile": "google", "block": 2}}')
vim.bo[java].shiftwidth = 4
finish_build(0)
spec.check("a build that wrote a launcher attaches the root's Java buffers", started[1], java)
spec.check("and gives them the project's indent", vim.bo[java].shiftwidth, 2)
spec.check("and names the version set up", last_note_has("Lathe: server 9.9.9 set up at"), true)

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
spec.check(
  "a failed fetch leaves no partial download",
  vim.uv.fs_stat(work .. "/cache/bootstrap/8.8.8/lib.fetching"),
  nil
)
spec.check(
  "but keeps the bootstrap POM for the retry",
  vim.uv.fs_stat(work .. "/cache/bootstrap/8.8.8/pom.xml") ~= nil,
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
spec.check(
  "a failed fetch keeps the jars fetched before",
  vim.uv.fs_stat(work .. "/cache/bootstrap/1.0-SNAPSHOT/lib") ~= nil,
  true
)
vim.env.LATHE_VERSION = "9.9.9"

local before = #calls
run_from("/multi")
spec.check("with the jars already fetched, it builds at once", #calls, before + 1)
spec.check("that call is the build", vim.tbl_contains(last_cmd(), "clean"), true)
finish_build(0)

-- The root prompt: an edited root is taken as typed; a cancel runs nothing.
next_answer = work .. "/multi/core/"
local notes_before = #notes
run_from("/multi/core")
spec.check("an edited root builds there", calls[#calls].opts.cwd, work .. "/multi/core")
spec.check(
  "a root inside the one found may be a module: say so",
  vim.iter(vim.list_slice(notes, notes_before + 1)):any(function(note)
    return note:find(
      work .. "/multi/core is inside the Maven build at " .. work .. "/multi",
      1,
      true
    ) ~= nil
  end),
  true
)
finish_build(0)

next_answer = "core"
vim.cmd.cd(work .. "/gradle")
vim.cmd.edit(work .. "/multi/core/pom.xml")
latheinit.run()
spec.check(
  "a relative root is taken from the root offered, not :pwd",
  calls[#calls].opts.cwd,
  work .. "/multi/core"
)
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

-- A build that pins another Lathe (a parent POM, say) runs its own: say which.
write(work .. "/multi/.lathe/workspace.json", '{"serverVersion": "1.2.3"}')
run_from("/multi")
spec.check(
  "the prompt names the version set up now",
  prompts[#prompts].prompt,
  "Lathe: set up Lathe 9.9.9 (now 1.2.3) for the Maven build in: "
)
finish_build(0)
spec.check(
  "a version the build pins is named instead of the one asked for",
  last_note_has("Lathe: server 1.2.3 set up at " .. work .. "/multi: the project's build pins it"),
  true
)

-- A build that changes the version the workspace records restarts the root's running servers, which
-- serve the old one, once they exited; a server of another root is left alone. A build that keeps
-- the version restarts nothing.
local here = server(work .. "/multi", "1.0.0")
local elsewhere = server(work .. "/gradle", "1.0.0")
clients = { here, elsewhere }
write(work .. "/multi/.lathe/workspace.json", '{"serverVersion": "1.0.0"}')
local starts = #started
run_from("/multi")
write(work .. "/multi/.lathe/workspace.json", '{"serverVersion": "1.2.3"}')
finish_build(0)
vim.wait(1000, function()
  return #started > starts
end)
spec.check("a server of the root is stopped", here.stopped, true)
spec.check("one of another root is left running", elsewhere.stopped, nil)
spec.check("the buffers start again once it exited", started[#started], java)
local same = server(work .. "/multi", nil)
clients = { same }
run_from("/multi")
finish_build(0)
spec.check("a build that keeps the version restarts nothing", same.stopped, nil)
clients = {}

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
spec.check(
  "and keeps the build's output, as a failure does",
  console_has("setup succeeded, with a warning"),
  true
)

-- A build that set up the workspace but could not fetch the server (the project's repositories
-- lack it): the warning says so, and the build's `[lathe]` line that says why stays in the split.
run_from("/marked/app")
write(work .. "/marked/app/.lathe/workspace.json", "{}")
finish_build(
  0,
  "> Task :latheSync\n[lathe] the Lathe server could not be resolved; not installed\n\n"
    .. "BUILD SUCCESSFUL in 9s\n3 actionable tasks: 3 executed"
)
spec.check(
  "a workspace set up without a server says so",
  last_note_has(
    "the build set up `.lathe/` at " .. work .. "/marked/app but installed no Lathe server"
  ),
  true
)
spec.check(
  "the build's reason stays in the split",
  console_has("[lathe] the Lathe server could not be resolved; not installed"),
  true
)
local console_win = vim.fn.bufwinid(vim.fn.bufnr("Lathe Sync Output"))
spec.check(
  "the split opens at it, not at the build's success below",
  vim.api.nvim_buf_get_lines(
    vim.fn.bufnr("Lathe Sync Output"),
    vim.api.nvim_win_get_cursor(console_win)[1] - 1,
    vim.api.nvim_win_get_cursor(console_win)[1],
    false
  )[1],
  "[lathe] the Lathe server could not be resolved; not installed"
)
vim.fn.delete(work .. "/marked/app/.lathe", "rf")

-- Gradle personal: the init script, written for the version.
run_from("/gradle/lib")
spec.check(
  "Gradle builds with the init script",
  table.concat(last_cmd(), " "),
  "/opt/bin/gradle --init-script "
    .. boot
    .. "/lathe.init.gradle.kts -Plathe.capture.only=true --continue latheSync"
)
local init_script = table.concat(vim.fn.readfile(boot .. "/lathe.init.gradle.kts"), "\n")
spec.check(
  "the init script names the plugin at the version",
  init_script:find('classpath("io.github.ag-libs:lathe-gradle-plugin:9.9.9")', 1, true) ~= nil
    and init_script:find("@VERSION@", 1, true) == nil,
  true
)
finish_build(0)

-- A workspace an earlier setup wrote says nothing about a build that failed before Lathe ran.
vim.uv.fs_utime(work .. "/team/.lathe/workspace.json", 1000, 1000)
run_from("/team")
finish_build(1)
spec.check(
  "a failed build with only an earlier setup's workspace keeps the setup error",
  last_note_has("setup failed") and not last_note_has("but the build failed"),
  true
)

-- A build that failed before Lathe set it up keeps the failed setup's error.
run_from("/gradle/lib")
finish_build(1)
spec.check(
  "a failed build without a launcher keeps the setup error",
  last_note_has("setup failed") and not last_note_has("but the build failed"),
  true
)

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
  "the prompt says the build registers Lathe",
  prompts[#prompts].prompt,
  "Lathe: build the Maven project, which registers Lathe, in: "
)
spec.check(
  "a registered Maven build runs the same first build, without the activation",
  table.concat(last_cmd(), " "),
  "/opt/bin/mvn -B -Dlathe.capture.only=true -DfailIfNoTests=false -Dmaven.compiler.failOnError=false clean test"
)
executable(work .. "/team/.lathe/lathe-launcher.sh")
write(work .. "/team/.lathe/workspace.json", '{"serverVersion": "9.9.9"}')
finish_build(1)
spec.check("a failed build that wrote a launcher attaches", started[#started], team_java)
spec.check(
  "and says once that Lathe is set up but the build failed",
  notes[#notes]:find(
    "server 9.9.9 set up at " .. work .. "/team, but the build failed (exit 1)",
    1,
    true
  )
      ~= nil
    and not vim.iter(notes):any(function(note)
      return note:find("setup failed", 1, true) ~= nil and note:find("team", 1, true) ~= nil
    end),
  true
)
run_from("/team-gradle")
local keep_errors = work .. "/cache/bootstrap/lathe.failonerror.gradle.kts"
spec.check(
  "a registered Gradle build keeps compile errors through an init script",
  table.concat(last_cmd(), " "),
  "/opt/bin/gradle --init-script "
    .. keep_errors
    .. " -Plathe.capture.only=true --continue latheSync"
)
local keep_errors_text = table.concat(vim.fn.readfile(keep_errors), "\n")
spec.check(
  "that script turns failOnError off and applies no plugin",
  keep_errors_text:find("options.isFailOnError = false", 1, true) ~= nil
    and keep_errors_text:find("classpath", 1, true) == nil,
  true
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

-- A root set up already runs nothing and names :LatheSync; forced, it builds again.
local function set_up(dir, version, script)
  executable(work .. dir .. "/.lathe/lathe-launcher.sh")
  write(work .. dir .. "/.lathe/workspace.json", ('{"serverVersion": "%s"}'):format(version))
  write(work .. dir .. "/.lathe/lathe-sync.sh", script or "#!/bin/sh")
  vim.fn.setfperm(work .. dir .. "/.lathe/lathe-sync.sh", "rwxr-xr-x")
end
write(work .. "/ready-team/.mvn/extensions.xml", "<artifactId>lathe-maven-extension</artifactId>")
write(work .. "/ready-team/pom.xml")
set_up("/ready-team", "7.0.0")
before = #calls
local prompts_at = #prompts
run_from("/ready-team")
spec.check("a registered build set up already runs nothing", #calls, before)
spec.check("and asks nothing", #prompts, prompts_at)
spec.check(
  "it names the version the build pins and :LatheSync",
  last_note_has(
    "server 7.0.0 is set up at " .. work .. "/ready-team by the project's build"
  ) and last_note_has("`:LatheSync` refreshes it, and `:LatheInit!` rebuilds it from clean."),
  true
)
vim.cmd.cd(work .. "/ready-team")
latheinit.run(nil, true)
spec.check(
  "forced, it asks to build again",
  prompts[#prompts].prompt,
  "Lathe: build the Maven project again, which registers Lathe, in: "
)
spec.check("and builds", #calls, before + 1)
finish_build(0)

-- :LatheSync needs the sync script and the server start an executable launcher: without either,
-- the root is not set up.
vim.fn.delete(work .. "/ready-team/.lathe/lathe-sync.sh")
before = #calls
run_from("/ready-team")
spec.check("a registered build without a sync script builds again", #calls, before + 1)
finish_build(0)
set_up("/ready-team", "7.0.0")
vim.fn.setfperm(work .. "/ready-team/.lathe/lathe-launcher.sh", "rw-r--r--")
before = #calls
run_from("/ready-team")
spec.check("a launcher that is not executable builds again", #calls, before + 1)
finish_build(0)
set_up("/ready-team", "7.0.0")
write(work .. "/ready-team/.lathe/workspace.json", '{"serverVersion": null}')
run_from("/ready-team")
spec.check(
  "a workspace that records no version names none",
  last_note_has("Lathe: Lathe is set up at " .. work .. "/ready-team by the project's build"),
  true
)
set_up("/ready-team", "7.0.0")

write(work .. "/no-launcher/pom.xml", "<artifactId>lathe-maven-extension</artifactId>")
write(work .. "/no-launcher/.lathe/workspace.json", '{"serverVersion": "7.0.0"}')
before = #calls
run_from("/no-launcher")
spec.check("a workspace without a launcher builds again", #calls, before + 1)
finish_build(0)

-- Registered since a personal setup: its script still brings the old Lathe in, over the pin.
set_up("/no-launcher", "7.0.0", "mvn -Dmaven.ext.class.path=" .. boot .. "/lib/lathe-core.jar")
before = #calls
run_from("/no-launcher")
spec.check("a registered build whose script replays an activation builds", #calls, before + 1)
finish_build(0)

-- Personal: set up when the sync script brings this version in for this kind.
write(work .. "/ready-maven/pom.xml")
set_up(
  "/ready-maven",
  "9.9.9",
  "mvn -Dmaven.ext.class.path=" .. boot .. "/lib/lathe-core-9.9.9.jar"
)
before = #calls
run_from("/ready-maven")
spec.check("a personal build at this version runs nothing", #calls, before)
spec.check(
  "and says it is set up",
  last_note_has("Lathe 9.9.9 is set up at " .. work .. "/ready-maven already"),
  true
)
set_up(
  "/ready-maven",
  "8.8.8",
  "mvn -Dmaven.ext.class.path=" .. work .. "/cache/bootstrap/8.8.8/lib/lathe-core-8.8.8.jar"
)
run_from("/ready-maven")
spec.check("another version builds", vim.tbl_contains(last_cmd(), "clean"), true)
finish_build(0)
set_up("/ready-maven", "9.9.9", "mvn test")
run_from("/ready-maven")
spec.check(
  "a script without the activation (it was registered) builds",
  vim.tbl_contains(last_cmd(), "clean"),
  true
)
finish_build(0)

write(work .. "/ready-mixed/pom.xml")
write(work .. "/ready-mixed/settings.gradle")
set_up("/ready-mixed", "9.9.9", "gradle --init-script " .. boot .. "/lathe.init.gradle.kts")
before = #calls
run_from("/ready-mixed", "gradle")
spec.check("a Gradle setup at this version runs nothing", #calls, before)
spec.check(
  "and says it sets it up again when forced",
  last_note_has("`:LatheInit!` sets it up again."),
  true
)
-- The script refuses to sync once its bootstrap file is gone: only a setup brings it back.
vim.fn.delete(boot .. "/lathe.init.gradle.kts")
run_from("/ready-mixed", "gradle")
spec.check("a setup whose bootstrap file is gone builds", #calls, before + 1)
finish_build(0)
run_from("/ready-mixed", "maven")
spec.check("Maven at a root set up through Gradle builds", vim.tbl_contains(last_cmd(), "clean"), true)
finish_build(0)

-- OpenJDK: set up at this version runs nothing; another version runs the goal.
set_up("/jdk", "9.9.9")
before = #calls
run_from("/jdk")
spec.check("an OpenJDK checkout at this version runs nothing", #calls, before)
vim.env.LATHE_VERSION = "9.9.10"
run_from("/jdk")
spec.check(
  "another version runs the goal",
  last_cmd()[3],
  "io.github.ag-libs:lathe-openjdk-maven-plugin:9.9.10:sync"
)
finish_build(0)
vim.env.LATHE_VERSION = "9.9.9"

-- A root typed in the prompt that is set up already runs nothing either.
before = #calls
next_answer = work .. "/ready-team"
run_from("/multi")
spec.check("a typed root set up already runs nothing", #calls, before)
spec.check(
  "and says so",
  last_note_has(work .. "/ready-team by the project's build"),
  true
)

-- A root with a run going asks nothing.
run_from("/multi")
prompts_at = #prompts
run_from("/multi/core")
spec.check("a root with a run going asks nothing", #prompts, prompts_at)
spec.check(
  "and says a run is going",
  last_note_has("already running at " .. work .. "/multi"),
  true
)
before = #calls
next_answer = work .. "/multi"
run_from("/ready-maven")
spec.check("a typed root with a run going runs nothing", #calls, before)
spec.check(
  "and says a run is going there",
  last_note_has("already running at " .. work .. "/multi"),
  true
)
next_answer = work .. "/multi/core"
run_from("/ready-maven")
spec.check("a typed root inside one with a run going runs nothing", #calls, before)
finish_build(0)

-- A root found from the current file outside :pwd says so in the prompt.
next_answer = false
vim.cmd.cd(work .. "/gradle")
vim.cmd.edit(work .. "/multi/core/pom.xml")
latheinit.run()
spec.check(
  "the prompt names a root found from the current file",
  prompts[#prompts].prompt,
  "Lathe: set up Lathe 9.9.9 (now 1.2.3) for the Maven build (found from the current file) in: "
)
next_answer = false
vim.cmd.cd(work .. "/multi")
latheinit.run()
spec.check(
  "but not one inside :pwd",
  prompts[#prompts].prompt,
  "Lathe: set up Lathe 9.9.9 (now 1.2.3) for the Maven build in: "
)
before = #calls

-- A development client sets up nothing without LATHE_VERSION, and says so before the prompt.
vim.env.LATHE_VERSION = nil
local prompts_before = #prompts
run_from("/gradle")
spec.check("a development version asks nothing", #prompts, prompts_before)
spec.check("a development version runs nothing", #calls, before)
spec.check("and says to set LATHE_VERSION", last_note_has("LATHE_VERSION"), true)
local dev_notes = #notes
run_from("/team-gradle")
spec.check("but builds a build that registers Lathe", #calls, before + 1)
spec.check(
  "without asking for LATHE_VERSION",
  vim.iter(vim.list_slice(notes, dev_notes + 1)):any(function(note)
    return note:find("LATHE_VERSION", 1, true) ~= nil
  end),
  false
)
finish_build(0)

latheinit.setup()
spec.check("registers :LatheInit", vim.fn.exists(":LatheInit"), 2)
spec.check("which takes a bang", vim.api.nvim_get_commands({}).LatheInit.bang, true)
spec.check(
  ":LatheInit completes the build kinds",
  table.concat(vim.fn.getcompletion("LatheInit ", "cmdline"), ","),
  "gradle,maven,openjdk"
)

vim.cmd.cd("/")
vim.fn.delete(work, "rf")
spec.finish()
