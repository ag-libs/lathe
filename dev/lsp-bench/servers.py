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
WORK = BENCH / ".work"
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


def gradle_init_script():
    script = WORK / "lathe.init.gradle.kts"
    repo = LATHE_GRADLE_REPO.as_uri()
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
""" % (repo, LATHE_VERSION, repo), encoding="utf-8")
    return script


def lathe_build_command(project, repo):
    if project["build"] == "gradle":
        return ["./gradlew", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process",
                "--init-script", str(gradle_init_script()), "latheSync", "-Plathe.capture.only=true"]
    maven = "./mvnw" if (repo / "mvnw").exists() else MAVEN
    return [maven, "-B", "-q"] + project.get("maven_goals", ["process-test-classes"])


def lathe_prepare(name, project, mode, log):
    """Cold: a fresh checkout's first build with Lathe, timed in ms. Warm: what the last run left."""
    repo = repo_of(name)
    if mode == "warm" and (repo / ".lathe" / "lathe-launcher.sh").exists():
        return None
    fresh_checkout(repo)
    if project["build"] == "maven":
        enable_lathe_maven(repo)
    start = time.monotonic()
    with open(log, "ab") as out:
        subprocess.run(lathe_build_command(project, repo), cwd=repo, env=build_env(project),
                       stdout=out, stderr=subprocess.STDOUT, check=True)
    return (time.monotonic() - start) * 1000


def lathe_server_version(repo):
    """The server version the build pinned in the workspace manifest."""
    manifest = repo / ".lathe" / "workspace.json"
    return json.loads(manifest.read_text(encoding="utf-8")).get("serverVersion") if manifest.exists() else None


def lathe_spec(name, project, state, heap):
    repo = repo_of(name)
    env = dict(os.environ)
    env.pop("LATHE_DEBUG", None)
    if heap:
        env["LATHE_JVM_OPTS"] = "-Xmx%s" % heap
    return {"cmd": [str(repo / ".lathe" / "lathe-launcher.sh")], "env": env, "init_options": {}, "settings": {}}


# -- jdtls ------------------------------------------------------------------------------------------

def jdtls_settings(project):
    # vscode-java's defaults where jdtls's own differ (signature help is off in jdtls itself). A
    # project with both a pom.xml and a Gradle build is imported through the build Lathe uses.
    java = {"signatureHelp": {"enabled": True}}
    if project["build"] == "maven":
        java["import"] = {"gradle": {"enabled": False}}
    return {"java": java}


def jdtls_config_dir():
    system = "mac" if platform.system() == "Darwin" else "linux"
    arm = platform.machine() in ("arm64", "aarch64")
    return "config_%s%s" % (system, "_arm" if arm else "")


def jdtls_prepare(name, project, mode, state):
    if mode == "cold":
        fresh_checkout(repo_of(name))
        shutil.rmtree(state, ignore_errors=True)
    state.mkdir(parents=True, exist_ok=True)
    return None


def jdtls_spec(name, project, state, heap):
    settings = jdtls_settings(project)
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
    "jdtls": {"prepare": lambda name, project, mode, state, log: jdtls_prepare(name, project, mode, state),
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
