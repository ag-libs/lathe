"""The pre-registered projects (README.md, Projects): where each comes from, its pinned commit, how it
builds, and its query file under cases/.

`main_module` is where the position rules (README.md, Queries) take their candidates: the module the
project's other modules build on, named before the project's first run.
`debug_main` is the main method the feature-parity check debugs (the 2026-11-19 amendment): its file
and its first statement, where the breakpoint goes; the main module's own, or the nearest a project has.
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
        # The branch switch's older commit: 65 back along the main line, the nearest at least 50 back
        # whose diff adds or deletes a main-code type (the 2026-11-19 amendment): 50 back touches none.
        "branch_ref": "b21890c126",
        # The build-file change: AssertJ, which the Spring Boot BOM manages and the local repository
        # holds through the test starters, on the main code's class path only once it is declared.
        # After the class line's blank line, as spring-javaformat, which the sync's build validates, wants.
        "build_change": {"build_file": "pom.xml", "after": "  <dependencies>\n",
                         "insert": "    <dependency>\n      <groupId>org.assertj</groupId>\n"
                                   "      <artifactId>assertj-core</artifactId>\n    </dependency>\n",
                         "file": "src/main/java/org/springframework/samples/petclinic/PetClinicApplication.java",
                         "anchor": "public class PetClinicApplication {\n\n",
                         "use": "\tpublic static final Class<?> BENCH_ASSERTJ = org.assertj.core.api.Assertions.class;\n\n"},
        "debug_main": ("src/main/java/org/springframework/samples/petclinic/PetClinicApplication.java",
                       "SpringApplication.run(PetClinicApplication.class, args);"),
        # WS-40: its nohttp check scans the whole project from the second build on and fails on the SBOM
        # Lathe's class mirror copies into .lathe/; Lathe's checkout takes the user guide's workaround
        # (troubleshooting.md), .lathe/ excluded as target/ is (README.md, the 2026-11-19 amendment).
        "lathe_replacements": {"pom.xml": (
            "<excludes>**/.git/**/*,**/.idea/**/*,**/target/**/,**/.flattened-pom.xml,**/*.class</excludes>",
            "<excludes>**/.git/**/*,**/.idea/**/*,**/target/**/,**/.flattened-pom.xml,**/*.class,**/.lathe/**/*</excludes>")},
    },
    "dropwizard": {
        "url": GIT_HOME / "dropwizard",
        "commit": "46bb4ed819",
        "build": "maven",
        "jdk": CORRETTO_25,
        "cases": "dropwizard",
        "main_module": "dropwizard-core",
        # The branch switch's older commit: 50 back along the main line (80 files, 38 Java).
        "branch_ref": "e357826376",
        # The build-file change: a dependency its BOM manages and the local repository holds, and
        # a use of one of its classes.
        "build_change": {"build_file": "dropwizard-core/pom.xml", "after": "    <dependencies>\n",
                         "insert": "        <dependency>\n            <groupId>org.jdbi</groupId>\n"
                                   "            <artifactId>jdbi3-core</artifactId>\n        </dependency>\n",
                         "file": "dropwizard-core/src/main/java/io/dropwizard/core/Application.java",
                         "anchor": "public abstract class Application<T extends Configuration> {\n",
                         # Public and not null: its NullAway and unused-field checks fail the build otherwise.
                         "use": "    public static final Class<?> BENCH_JDBI = org.jdbi.v3.core.Jdbi.class;\n"},
        # Its pgpverify check rejects an unsigned artifact; Lathe's releases are signed, the pinned
        # local build is not, so Lathe's build allows its group unsigned and verifies all the rest.
        "lathe_appends": {"pgp-keys-map.list": "io.github.ag-libs = noSig\n"},
        "debug_main": ("dropwizard-example/src/main/java/com/example/helloworld/HelloWorldApplication.java",
                       "new HelloWorldApplication().run(args);"),
    },
    "helidon": {
        "url": GIT_HOME / "helidon",
        "commit": "2f6df16425",
        "build": "maven",
        "jdk": CORRETTO_27,
        "cases": "helidon",
        "main_module": "webserver/webserver",
        # The branch switch's older commit: 50 back along the main line (1,005 files, 354 Java).
        "branch_ref": "c2fe76b86e",
        # jdtls's Maven integration resolves the compiler plugin's dependencies (Helidon's own
        # annotation processors) only from a repository, so its runs install Helidon first, into a
        # repository of their own; the install is timed as jdtls's build.
        "jdtls_install": True,
        # Its compiler plugin takes those processors from the reactor, so the errors-against-the-build
        # builds (stage2.py) build the whole reactor, not the target modules and what they depend on.
        "errors_whole_reactor": True,
        # The webserver's own main lives in its tests' application module; its config is generated.
        "debug_main": ("webserver/tests/imperative/src/main/java/io/helidon/webserver/tests/imperative/ImperativeMain.java",
                       "Config config = Services.get(Config.class);"),
    },
    "hibernate-orm": {
        "url": GIT_HOME / "hibernate-orm",
        "commit": "37616099b7",
        "build": "gradle",
        "jdk": CORRETTO_25,
        "cases": "hibernate_orm",
        "main_module": "hibernate-core",
        # 50 back along the main line (394 files, 255 Java); setup.sh fetches the history.
        "branch_ref": "caa0158c",
        # The build-file change: a library another of its modules uses (hibernate-hikaricp), so the
        # Gradle cache holds it, and a use of one of its classes.
        "build_change": {"build_file": "hibernate-core/hibernate-core.gradle", "after": "dependencies {\n",
                         "insert": "    implementation libs.hikaricp\n",
                         "file": "hibernate-core/src/main/java/org/hibernate/Version.java",
                         "anchor": "public final class Version {\n",
                         "use": "    public static final Class<?> BENCH_HIKARI = com.zaxxer.hikari.HikariConfig.class;\n"},
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
        # 50 back along the main line (1,683 files, 260 Java); setup.sh fetches the blobs.
        "branch_ref": "44ce7aa42b",
        # Through package, so the shaded module's jar exists; without the UI, distribution and
        # generated-client modules (README.md, Projects). Tests captured, not run, as the README's
        # first build; the baseline compiles the same modules without Lathe.
        "maven_goals": ["package", "-Dlathe.capture.only=true", "-Dlathe.sync.phase=package",
                        "-pl", "!openmetadata-ui,!openmetadata-dist,!openmetadata-clients"],
        "baseline_goals": ["package", "-DskipTests",
                           "-pl", "!openmetadata-ui,!openmetadata-dist,!openmetadata-clients"],
    },
}
