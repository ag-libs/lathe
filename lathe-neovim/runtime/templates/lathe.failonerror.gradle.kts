// Written by :LatheInit for a Gradle build that registers Lathe itself, so code that does not
// compile still sets up, as with lathe.init.gradle.kts. Passed with --init-script; the sync script
// the build writes replays it.

// Incremental compilation is off, as after a compile with errors that Gradle counted as done it
// would recompile only the changed sources and never write the classes that compile left out. The
// root build only, as the Lathe plugin: an init script reaches every build Gradle loads, buildSrc
// and included build logic too, whose compile errors must still stop them.
if (gradle.parent == null) {
    gradle.lifecycle.beforeProject {
        tasks.withType<JavaCompile>().configureEach {
            options.isFailOnError = false
            options.isIncremental = false
        }
    }
}
