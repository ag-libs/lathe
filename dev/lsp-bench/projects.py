"""The pre-registered projects (README.md, Projects): where each comes from, its pinned commit, how it
builds, and its query file under cases/.

`main_module` is where the position rules (README.md, Queries) take their candidates: the module the
project's other modules build on, named before the project's first run.
"""
import os
from pathlib import Path

GIT_HOME = Path(os.environ.get("BENCH_GIT_HOME", Path.home() / "work/git"))
CORRETTO_25 = os.environ.get("JAVA25_HOME", "/opt/amazon-corretto-25.0.0.36.2-linux-x64")
CORRETTO_27 = os.environ.get("JAVA27_HOME", "/opt/amazon-corretto-27.0.0.35.1-linux-x64")
CORRETTO_21 = os.environ.get("JAVA21_HOME", "/opt/amazon-corretto-21.0.8.9.1-linux-x64")

PROJECTS = {
    "spring-petclinic": {
        # java-index's harness clones it at the same commit; GitHub otherwise.
        "url": next((str(p) for p in [GIT_HOME / "java-index/benchmark/.work/repos/spring-petclinic"] if p.is_dir()),
                    "https://github.com/spring-projects/spring-petclinic.git"),
        "commit": "500158f732419217507c7656904b8e6aa1bcc0d6",
        "build": "maven",
        "jdk": CORRETTO_25,
        "cases": "spring_petclinic",
        "main_module": ".",
    },
    "dropwizard": {
        "url": GIT_HOME / "dropwizard",
        "commit": "46bb4ed819",
        "build": "maven",
        "jdk": CORRETTO_25,
        "cases": "dropwizard",
        "main_module": "dropwizard-core",
        # Its pgpverify check rejects an unsigned artifact; Lathe's releases are signed, the pinned
        # local build is not, so Lathe's build allows its group unsigned and verifies all the rest.
        "lathe_appends": {"pgp-keys-map.list": "io.github.ag-libs = noSig\n"},
    },
    "helidon": {
        "url": GIT_HOME / "helidon",
        "commit": "2f6df16425",
        "build": "maven",
        "jdk": CORRETTO_27,
        "cases": "helidon",
        "main_module": "webserver/webserver",
        # jdtls's Maven integration resolves the compiler plugin's dependencies (Helidon's own
        # annotation processors) only from a repository, so its runs install Helidon first, into a
        # repository of their own; the install is timed as jdtls's build.
        "jdtls_install": True,
    },
    "hibernate-orm": {
        "url": GIT_HOME / "hibernate-orm",
        "commit": "37616099b7",
        "build": "gradle",
        "jdk": CORRETTO_25,
        "cases": "hibernate_orm",
        "main_module": "hibernate-core",
    },
    "fineract": {
        "url": GIT_HOME / "fineract",
        "commit": "d63beed7cc",
        "build": "gradle",
        "jdk": CORRETTO_25,
        "cases": "fineract",
        "main_module": "fineract-core",
        # It declares its repositories per project, so the pinned Lathe's repository goes there too.
        "lathe_project_repository": True,
        "lombok": "1.18.46",
    },
    "openmetadata": {
        "url": GIT_HOME / "openmetadata",
        "commit": "9a2ee8b124",
        "build": "maven",
        # It targets Java 21, and its Lombok (1.18.36) predates JDK 25.
        "jdk": CORRETTO_21,
        "cases": "openmetadata",
        "main_module": "openmetadata-service",
        "lombok": "1.18.36",
        # Through package, so the shaded module's jar exists; without the UI, distribution and
        # generated-client modules (README.md, Projects).
        "maven_goals": ["package", "-DskipTests", "-Dlathe.sync.phase=package",
                        "-pl", "!openmetadata-ui,!openmetadata-dist,!openmetadata-clients"],
    },
}
