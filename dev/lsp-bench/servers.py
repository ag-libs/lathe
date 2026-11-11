"""How each server is prepared and started, as its users get it (README.md, Servers).

Lathe: the build writes `.lathe/` (Maven through the extension in `.mvn/extensions.xml`, Gradle through
the settings plugin from an init script), then the editor starts `.lathe/lathe-launcher.sh`.
jdtls: started the way vscode-java starts it, importing the project itself.
Each server gets the clone as a fresh checkout on its cold runs, so neither sees the other's files.
"""
import glob
import json
import os
import platform
import shutil
import subprocess
import time
from pathlib import Path

import projects as P

BENCH = Path(__file__).resolve().parent
# BENCH_WORK: another checkout's work directory, so a worktree needs no copy of the clones and servers.
WORK = Path(os.environ.get("BENCH_WORK", str(BENCH / ".work")))
REPOS = WORK / "repos"
SERVERS = WORK / "servers"
LATHE_VERSION = os.environ.get("BENCH_LATHE_VERSION", "0.3.99-c15d3a55")
# The Gradle plugin and the Lathe jars it resolves, copied from the build by setup.sh.
LATHE_GRADLE_REPO = WORK / "lathe-gradle-repo"
JDTLS = SERVERS / "jdtls"
JDTLS_JAVA_HOME = P.CORRETTO_25
MAVEN = os.environ.get("BENCH_MAVEN", "/opt/maven-mvnd-1.0.3-linux-amd64/mvn/bin/mvn")
LATHE_EXTENSION = """    <extension>
        <groupId>io.github.ag-libs</groupId>
        <artifactId>lathe-maven-extension</artifactId>
        <version>%s</version>
    </extension>
"""
EMPTY_EXTENSIONS = '<?xml version="1.0" encoding="UTF-8"?>\n<extensions>\n</extensions>\n'


def repo_of(name):
    return REPOS / name


def fresh_checkout(repo):
    """The clone as a fresh clone has it: no build output, no server files, no local edits."""
    subprocess.run(["git", "checkout", "-q", "--", "."], cwd=repo, check=True)
    subprocess.run(["git", "clean", "-q", "-ffdx"], cwd=repo, check=True)


def build_env(project):
    return {**os.environ, "JAVA_HOME": project["jdk"],
            "PATH": "%s/bin%s%s" % (project["jdk"], os.pathsep, os.environ["PATH"])}


# -- Lathe ------------------------------------------------------------------------------------------

def enable_lathe_maven(repo):
    extensions = repo / ".mvn" / "extensions.xml"
    extensions.parent.mkdir(exist_ok=True)
    text = extensions.read_text(encoding="utf-8") if extensions.exists() else EMPTY_EXTENSIONS
    extensions.write_text(text.replace("</extensions>", LATHE_EXTENSION % LATHE_VERSION + "</extensions>"),
                          encoding="utf-8")


def gradle_init_script(project):
    script = WORK / "lathe.init.gradle.kts"
    repo = LATHE_GRADLE_REPO.as_uri()
    # A build that declares its repositories per project ignores the settings' ones; a released
    # Lathe comes from Maven Central, which such a build declares, the pinned local one does not.
    per_project = """allprojects {
  repositories { maven { url = uri("%s"); content { includeGroup("io.github.ag-libs") } } }
}
""" % repo if project.get("lathe_project_repository") else ""
    script.write_text("""initscript {
  repositories { maven { url = uri("%s") }; mavenCentral(); gradlePluginPortal() }
  dependencies { classpath("io.github.ag-libs:lathe-gradle-plugin:%s") }
}
settingsEvaluated {
  dependencyResolutionManagement.repositories.maven { url = uri("%s") }
  if (!pluginManager.hasPlugin("io.github.ag-libs.lathe")) {
    pluginManager.apply(io.github.aglibs.lathe.gradle.LatheSettingsPlugin::class.java)
  }
}
""" % (repo, LATHE_VERSION, repo) + per_project, encoding="utf-8")
    return script


def lathe_build_command(project, repo):
    if project["build"] == "gradle":
        return ["./gradlew", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process",
                "--init-script", str(gradle_init_script(project)), "latheSync", "-Plathe.capture.only=true"]
    # The README's first build: tests captured (in a dry run), not run (the 2026-11-11 amendment).
    return maven_command(repo) + project.get("maven_goals", ["clean", "test", "-Dlathe.capture.only=true"])


def maven_command(repo):
    return ["./mvnw" if (repo / "mvnw").exists() else MAVEN, "-B", "-q"]


def baseline_command(project, repo):
    """What compiles the code and its tests with no Lathe loaded: the build a project costs anyway."""
    if project["build"] == "gradle":
        return ["./gradlew", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process", "testClasses"]
    return maven_command(repo) + project.get("baseline_goals", ["clean", "test-compile"])


def baseline_build(name, project, log):
    """The baseline on a fresh checkout, timed in ms; the checkout carries no Lathe extension."""
    repo = repo_of(name)
    fresh_checkout(repo)
    start = time.monotonic()
    with open(log, "ab") as out:
        subprocess.run(baseline_command(project, repo), cwd=repo, env=build_env(project),
                       stdout=out, stderr=subprocess.STDOUT, check=True)
    return (time.monotonic() - start) * 1000


def lathe_prepare(name, project, mode, log):
    """Cold: a fresh checkout's first build with Lathe, timed in ms. Warm: what the last run left."""
    repo = repo_of(name)
    if mode == "warm" and (repo / ".lathe" / "lathe-launcher.sh").exists():
        return None
    fresh_checkout(repo)
    if project["build"] == "maven":
        enable_lathe_maven(repo)
    for path, line in project.get("lathe_appends", {}).items():
        with open(repo / path, "a", encoding="utf-8") as f:
            f.write(line)
    start = time.monotonic()
    with open(log, "ab") as out:
        subprocess.run(lathe_build_command(project, repo), cwd=repo, env=build_env(project),
                       stdout=out, stderr=subprocess.STDOUT, check=True)
    return (time.monotonic() - start) * 1000


def lathe_server_version(repo):
    """The server version the build pinned in the workspace manifest."""
    manifest = repo / ".lathe" / "workspace.json"
    return json.loads(manifest.read_text(encoding="utf-8")).get("serverVersion") if manifest.exists() else None


def lathe_spec(name, project, state, heap, overrides=None):
    repo = repo_of(name)
    env = dict(os.environ)
    env.pop("LATHE_DEBUG", None)
    if heap:
        env["LATHE_JVM_OPTS"] = "-Xmx%s" % heap
    return {"cmd": [str(repo / ".lathe" / "lathe-launcher.sh")], "env": env, "init_options": {}, "settings": {}}


# -- jdtls ------------------------------------------------------------------------------------------

def jdtls_repository(name):
    """The Maven repository of jdtls's runs on a project it needs installed, and its settings file."""
    repository = WORK / ("m2-%s" % name)
    settings = WORK / ("m2-%s-settings.xml" % name)
    settings.write_text("<settings><localRepository>%s</localRepository></settings>\n" % repository,
                        encoding="utf-8")
    return repository, settings


def jdtls_settings(name, project):
    # vscode-java's defaults where jdtls's own differ (signature help is off in jdtls itself). A
    # project with both a pom.xml and a Gradle build is imported through the build Lathe uses.
    java = {"signatureHelp": {"enabled": True}}
    if project["build"] == "maven":
        java["import"] = {"gradle": {"enabled": False}}
    if project.get("jdtls_install"):
        java["configuration"] = {"maven": {"userSettings": str(jdtls_repository(name)[1])}}
    return {"java": java}


def jdtls_config_dir():
    system = "mac" if platform.system() == "Darwin" else "linux"
    arm = platform.machine() in ("arm64", "aarch64")
    return "config_%s%s" % (system, "_arm" if arm else "")


def jdtls_prepare(name, project, mode, state, log):
    """Cold: a fresh checkout and no jdtls workspace; the project installed first where jdtls needs
    it, timed in ms. Warm: what the last run left."""
    if mode == "warm":
        state.mkdir(parents=True, exist_ok=True)
        return None
    repo = repo_of(name)
    fresh_checkout(repo)
    shutil.rmtree(state, ignore_errors=True)
    state.mkdir(parents=True, exist_ok=True)
    if not project.get("jdtls_install"):
        return None
    repository, _settings = jdtls_repository(name)
    maven = "./mvnw" if (repo / "mvnw").exists() else MAVEN
    start = time.monotonic()
    with open(log, "ab") as out:
        subprocess.run([maven, "-B", "-q", "install", "-DskipTests", "-Dmaven.repo.local=%s" % repository],
                       cwd=repo, env=build_env(project), stdout=out, stderr=subprocess.STDOUT, check=True)
    # jdtls then opens the tree the install left, as its user would after `mvn install`.
    return (time.monotonic() - start) * 1000


def lombok_jar(version):
    """The project's own Lombok jar, from the Maven or Gradle cache its build filled."""
    candidates = [Path.home() / ".m2/repository/org/projectlombok/lombok" / version / ("lombok-%s.jar" % version)]
    candidates += sorted((Path.home() / ".gradle/caches/modules-2/files-2.1/org.projectlombok/lombok" / version)
                         .glob("*/lombok-%s.jar" % version))
    found = next((c for c in candidates if c.exists()), None)
    if found is None:
        raise SystemExit("no lombok-%s.jar in the Maven or Gradle cache: build the project first" % version)
    return found


def merge(settings, overrides):
    """Settings with dotted keys (java.import.gradle.enabled=false) set, for a labelled variant."""
    for key, value in overrides.items():
        node = settings
        parts = key.split(".")
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = value
    return settings


def jdtls_spec(name, project, state, heap, overrides=None):
    settings = merge(jdtls_settings(name, project), overrides or {})
    launcher = glob.glob(str(JDTLS / "plugins" / "org.eclipse.equinox.launcher_*.jar"))[0]
    # vscode-java's default VM arguments (java.jdt.ls.vmargs) and its launch of the product.
    cmd = [
        str(Path(JDTLS_JAVA_HOME) / "bin" / "java"),
        "-Declipse.application=org.eclipse.jdt.ls.core.id1",
        "-Dosgi.bundles.defaultStartLevel=4",
        "-Declipse.product=org.eclipse.jdt.ls.core.product",
        "-Dosgi.checkConfiguration=true",
        "-Dosgi.sharedConfiguration.area=%s" % (JDTLS / jdtls_config_dir()),
        "-Dosgi.sharedConfiguration.area.readOnly=true",
        "-Dosgi.configuration.cascaded=true",
        "-XX:+UseParallelGC", "-XX:GCTimeRatio=4", "-XX:AdaptiveSizePolicyWeight=90",
        "-Dsun.zip.disableMemoryMapping=true",
        "-Xmx%s" % (heap or "2G"), "-Xms100m", "-Xlog:disable",
        "--add-modules=ALL-SYSTEM",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    ]
    # vscode-java starts jdtls with a Lombok agent (java.jdt.ls.lombokSupport.enabled, on by default),
    # the project's own Lombok where the project depends on it.
    if project.get("lombok"):
        cmd.append("-javaagent:%s" % lombok_jar(project["lombok"]))
    cmd += [
        "-jar", launcher,
        "-configuration", str(state / "config"),
        "-data", str(state / "workspace"),
    ]
    env = {**os.environ, "JAVA_HOME": JDTLS_JAVA_HOME}
    init_options = {"settings": settings,
                    "extendedClientCapabilities": {"classFileContentsSupport": True}}
    return {"cmd": cmd, "env": env, "init_options": init_options, "settings": settings}


SERVERS_BY_NAME = {
    "lathe": {"prepare": lambda name, project, mode, state, log: lathe_prepare(name, project, mode, log),
              "spec": lathe_spec},
    "jdtls": {"prepare": jdtls_prepare,
              "spec": jdtls_spec},
}


def versions(project):
    """Every JDK, server and project version a run records."""
    def java_version(home):
        out = subprocess.run([str(Path(home) / "bin" / "java"), "-version"], capture_output=True, text=True)
        return out.stderr.splitlines()[0] if out.stderr else None

    tarballs = glob.glob(str(SERVERS / "jdt-language-server-*.tar.gz"))
    return {
        "lathe": LATHE_VERSION,
        "jdtls": os.path.basename(tarballs[0]) if tarballs else None,
        "build_jdk": java_version(project["jdk"]),
        "jdtls_jdk": java_version(JDTLS_JAVA_HOME),
    }
