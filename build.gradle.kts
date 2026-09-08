
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

version = "0.1.0"
base { archivesName.set("AE-Inspector") }

// Opt-in development harness; never included in the shipped mod JAR.
val smoke by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + configurations.testRuntimeClasspath.get()
}
tasks.named<JavaCompile>(smoke.compileJavaTaskName) {
    options.annotationProcessorPath = tasks.named<JavaCompile>("compileJava").get().options.annotationProcessorPath
}
tasks.register<Jar>("smokeJar") {
    dependsOn(smoke.classesTaskName)
    from(smoke.output)
    archiveFileName.set("aeinspector-smoketest.jar")
    destinationDirectory.set(layout.projectDirectory.dir("run/client/mods"))
}

tasks.register<JavaExec>("benchmarkCore") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.aeinspector.storage.CoreBenchmark")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "1G"
    if (providers.gradleProperty("profileCore").isPresent) {
        jvmArgs("-XX:StartFlightRecording=settings=profile,filename=" + layout.buildDirectory.file("core-profile.jfr").get().asFile.absolutePath)
    }
    args(layout.buildDirectory.dir("core-benchmark").get().asFile.absolutePath)
}
