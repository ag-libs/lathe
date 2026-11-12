// Written by :LatheInit for Lathe @VERSION@: brings Lathe into a Gradle build that does not register
// it, without changing the build. Passed with --init-script; the sync script it writes replays it.
initscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("io.github.ag-libs:lathe-gradle-plugin:@VERSION@") }
}

settingsEvaluated {
    if (!pluginManager.hasPlugin("io.github.ag-libs.lathe")) {
        pluginManager.apply(io.github.aglibs.lathe.gradle.LatheSettingsPlugin::class.java)
    }
}

// Code that does not compile still sets up: every project is captured, and the server reports the
// errors. Incremental compilation is off, as after a compile with errors that Gradle counted as
// done it would recompile only the changed sources and never write the classes that compile left
// out.
allprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.isFailOnError = false
        options.isIncremental = false
    }
}
