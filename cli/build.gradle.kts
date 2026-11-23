import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration
import java.util.spi.ToolProvider

// The superdevkit command (ADR-6F3CJZBY; docs/archive/superdevkit-transition.md, 7.4): init, build and run
// come with M7. It builds the distribution: both launchers in bin/, the jars in java/, dampf's
// headers in include/ (ADR-6F60Z9QJ), installed to the root's build/install/superdevkit.
evaluationDependsOn(":compiler")   // its launcher and headers go into the distribution

plugins {
    application
}

dependencies {
    implementation(project(":compiler"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir   // the tests read tools/ and write their files as the compiler's do
    environment("SFX_GOLDEN", System.getenv("SFX_GOLDEN") ?: "")
    inputs.file(rootProject.file("tools/brr_lab.html"))
    inputs.files(rootProject.fileTree("tools/lab_test") { include("*.js", "package*.json") })
    dependsOn(tasks.installDist)   // BrrLabTest compares the Lab with the installed superdevkit sfx
    filter { isFailOnNoMatchingTests = false }   // `./gradlew test --tests X`: X may be the compiler's
    timeout.set(Duration.ofMinutes(10))
}

application {
    mainClass = "superdevkit.Main"
    applicationName = "superdevkit"
    // dampf runs in the same Java, and finds its headers in the installation's include/
    applicationDefaultJvmArgs = listOf("-Ddampf.include=__APP_HOME__/include")
}

distributions {
    main {
        distributionBaseName = "superdevkit"
        contents {
            from(project(":compiler").tasks.named("startScripts")) { into("bin") }
            from(project(":compiler").file("src/main/resources/dampf/include")) { into("include") }
            // the kit, so that the installation is a superdevkit home with all a game needs
            // (ADR-6F60Z9QJ): the library, the tools, the examples, dev/replay.sh (a recording on the SNES side)
            from(rootProject.file("lib")) { into("lib") }
            from(rootProject.file("tools")) { into("tools"); exclude("**/__pycache__/**", "lab_test/**") }
            from(rootProject.file("examples")) { into("examples"); exclude("*/build/**", "**/__pycache__/**") }
            from(rootProject.file("dev")) {
                into("dev")
                include("replay.sh")
            }
            from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
            // the book as Markdown: its links (/glossary#x, VitePress's) become relative .md links,
            // which work in an editor, on GitHub and for an AI reading the files
            from(rootProject.file("docs/book")) {
                into("docs")
                include("glossary.md", "guide/**", "reference/**", "inside/**")
                eachFile {
                    val up = "../".repeat(sourcePath.count { it == '/' })
                    filter { line ->
                        line.replace(Regex("""\]\(/([a-z0-9/-]+)(#[^)]*)?\)""")) {
                            "](" + up + it.groupValues[1] + ".md" + it.groupValues[2] + ")"
                        }
                    }
                }
            }
            eachFile {   // the jars in java/: lib/ is the kit's library in a release
                if (name.endsWith(".jar") && relativePath.segments.first() == "lib")
                    relativePath = RelativePath(true, "java", *relativePath.segments.drop(1).toTypedArray())
            }
            includeEmptyDirs = false
        }
    }
}

tasks.installDist {
    destinationDir = rootProject.layout.buildDirectory.dir("install/superdevkit").get().asFile
    // Gradle recognises an earlier installation by its lib/ folder, which is java/ here, and would
    // refuse to install again: the folder is this task's alone, so it is emptied first
    val dir = destinationDir
    doFirst { dir.deleteRecursively() }
}

tasks.jar { archiveBaseName = "superdevkit" }

// The release zip of the system this runs on (ADR-6F60Z9QJ): the installation, a Java runtime cut
// down to the modules the jars use, and raylib for this system and the web. A runtime is cut on
// the system it is for, so GitHub Actions builds each zip there (.github/workflows/release.yml).
val releaseSystem = if (System.getProperty("os.name").lowercase().startsWith("windows")) "windows" else "linux"
val releaseWork = layout.buildDirectory.dir("release")

val releaseJre = tasks.register("releaseJre") {
    description = "Cuts the Java runtime of a release down to the modules the jars use"
    val jars = files(tasks.jar, configurations.runtimeClasspath)
    val output = releaseWork.map { it.dir("jre") }
    inputs.files(jars)
    inputs.property("java", System.getProperty("java.runtime.version"))
    outputs.dir(output)
    doLast {
        fun tool(name: String, vararg args: String): String {
            val text = StringWriter()
            val writer = PrintWriter(text)
            val status = ToolProvider.findFirst(name).orElseThrow().run(writer, writer, *args)
            writer.flush()
            check(status == 0) { "$name failed:\n$text" }
            return text.toString()
        }
        val out = output.get().asFile
        out.deleteRecursively()   // jlink refuses a folder that exists
        val paths = jars.files.map { it.path }
        val modules = tool("jdeps", "--multi-release", "25", "--ignore-missing-deps", "--print-module-deps",
            "--class-path", paths.joinToString(File.pathSeparator), *paths.toTypedArray()).trim().lines().last()
        tool("jlink", "--add-modules", modules, "--strip-debug", "--no-man-pages", "--no-header-files",
            "--compress=zip-9", "--output", out.path)
    }
}

val releaseRaylib = tasks.register<JavaExec>("releaseRaylib") {
    description = "Fetches the pinned raylib of this system and the web for a release"
    val output = releaseWork.map { it.dir("home") }
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "superdevkit.project.Raylib"
    args(output.get().asFile.path, if (releaseSystem == "windows") "win64" else "linux", "web")
    outputs.dir(output)
}

tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "Writes build/release/superdevkit-<version>-<system>-x64.zip for this system"
    destinationDirectory = rootProject.layout.buildDirectory.dir("release")
    archiveFileName = "superdevkit-$version-$releaseSystem-x64.zip"
    useFileSystemPermissions()   // Gradle would drop the launchers' and the runtime's executable bits
    val text = "$version\n"
    into("superdevkit-$version") {
        from(tasks.installDist)
        from(releaseJre) { into("jre") }
        from(releaseRaylib)
        from(resources.text.fromString(text)) { rename { "VERSION" } }
    }
    val arch = System.getProperty("os.arch")
    doFirst { check(arch == "amd64") { "a release is for x64, this machine is $arch" } }
}
