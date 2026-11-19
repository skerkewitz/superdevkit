import java.time.Duration

// The superdevkit command (ADR record 31; docs/superdevkit-transition.md, 7.4): init, build and run
// come with M7. It builds the distribution: both launchers in bin/, the jars in java/, dampf's
// headers in include/ (ADR record 41), installed to the root's build/install/superdevkit.
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
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

tasks.test {
    useJUnitPlatform()
    filter { isFailOnNoMatchingTests = false }   // `./gradlew test --tests X`: X may be the compiler's
    timeout.set(Duration.ofMinutes(10))
}

application {
    mainClass = "superdevkit.Main"
    applicationName = "superdevkit"
    // dampf runs in the same Java, and finds its headers in the installation's include/
    applicationDefaultJvmArgs = listOf("-Ddampf.include=__APP_HOME__/include")
}

tasks.startScripts {
    doLast {
        unixScript.writeText(unixScript.readText().replace("__APP_HOME__", "'\"\$APP_HOME\"'")
            .replace("\$APP_HOME/lib/", "\$APP_HOME/java/"))
        windowsScript.writeText(windowsScript.readText().replace("__APP_HOME__", "%APP_HOME%")
            .replace("%APP_HOME%\\lib\\", "%APP_HOME%\\java\\"))
    }
}

distributions {
    main {
        distributionBaseName = "superdevkit"
        contents {
            from(project(":compiler").tasks.named("startScripts")) { into("bin") }
            from(project(":compiler").file("src/main/resources/dampf/include")) { into("include") }
            // the kit, so that the installation is a superdevkit home with all a game needs
            // (ADR record 41): the library, the tools, the examples, the desktop's build scripts
            from(rootProject.file("lib")) { into("lib") }
            from(rootProject.file("tools")) { into("tools"); exclude("**/__pycache__/**") }
            from(rootProject.file("examples")) { into("examples"); exclude("*/build/**", "**/__pycache__/**") }
            from(rootProject.file("dev")) {
                into("dev")
                include("build_desktop.sh", "build_desktop_windows.sh", "fetch_raylib.sh", "check_game_symbols.py", "replay.sh")
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
