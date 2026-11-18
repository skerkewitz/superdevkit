import java.time.Duration

plugins {
    application
    antlr
    jacoco
    pmd
}

group = "dampf"
// The version (ADR record 26): milestone N is 0.N.0. dampf --version prints it.
version = "0.4.0"

repositories {
    mavenCentral()
}


val coverage = providers.gradleProperty("coverage").isPresent

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2") // reads the CPU test suite
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.generateGrammarSource {
    // The grammar sits in src/main/antlr/dampf/parser, so the generated files land in the right package folder.
    // -lib lets the parser grammar find CLexer.tokens, which the lexer grammar generates there.
    val generated = outputDirectory.resolve("dampf/parser")
    arguments = arguments + listOf("-visitor", "-no-listener", "-package", "dampf.parser", "-lib", generated.path)
    doFirst { generated.mkdirs() }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Language level and API of the current LTS release (25); any newer JDK can build it.
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // a last resort: the program tests have their own limits (a hang fails the test, see ExecutionTest)
    timeout.set(Duration.ofHours(2))
    // Files the tests read that are not on the class path: without them as inputs, a change to
    // a document, the game's snapshot or the platform layer left the task up to date, and
    // BookSourcesTest, AdrTest and the game's tests did not run again.
    inputs.files(
        fileTree("docs") { exclude("book/node_modules/**", "book/.vitepress/cache/**", "book/.vitepress/dist/**") },
        fileTree("doc-specs"), fileTree("examples"), fileTree("platform"), fileTree("reference"),
        fileTree("tools"), "README.md", "CLAUDE.md",
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("documentsAndExamples")
    // Tests that need the Blubber Blasen sources read their location from this property.
    providers.gradleProperty("blubberblasen").orNull?.let { systemProperty("dampf.blubberblasen", it) }
    // Execution tests run ROMs in Mesen 2: -Pmesen=/path/to/Mesen.exe, or the one in .mesen/
    val mesen = providers.gradleProperty("mesen").orNull
        ?: layout.projectDirectory.file(".mesen/Mesen.exe").asFile.takeIf { it.exists() }?.path
    mesen?.let { systemProperty("dampf.mesen", it) }
    // Random programs: -Prandom=<count> (default 50), -PrandomSeed=<first seed>
    providers.gradleProperty("random").orNull?.let { systemProperty("dampf.random.count", it) }
    providers.gradleProperty("randomSeed").orNull?.let { systemProperty("dampf.random.seed", it) }
    // Replays of the game and its timing check (minutes, in dampf's emulator): -Preplays
    providers.gradleProperty("replays").orNull?.let { systemProperty("dampf.replays", "true") }
    // Records new hashes of the back end's output (BackendOutputTest): -PbackendOutput=update
    providers.gradleProperty("backendOutput").orNull?.let { systemProperty("dampf.backendOutput", it) }
    // The 65816 emulator against the SingleStepTests suite (downloads ~50 MB once): -PcpuTests
    providers.gradleProperty("cpuTests").orNull?.let { systemProperty("dampf.cpuTests", it.ifEmpty { "300" }) }
    // Code coverage (JaCoCo): -Pcoverage, report in build/reports/jacoco/test/html/index.html.
    // Off by default: the agent slows down the tests that run the emulator.
    extensions.configure<JacocoTaskExtension> { isEnabled = coverage }
    if (coverage) finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.16"
}

tasks.jacocoTestReport {
    onlyIf { coverage }
    reports {
        xml.required = true // for tools (IDE, CI)
        html.required = true
    }
    // The ANTLR-generated parser is not dampf's code.
    classDirectories.setFrom(classDirectories.files.map { fileTree(it) {
        exclude("dampf/parser/CLexer.class", "dampf/parser/CParser.class", "dampf/parser/CParser$*",
            "dampf/parser/CParserVisitor*", "dampf/parser/CParserBaseVisitor*")
    } })
}

// Static analysis (PMD): ./gradlew pmdMain, report in build/reports/pmd/main.html.
// Not part of `build`: the findings are for reading, not a gate.
pmd {
    toolVersion = "7.29.0"
    ruleSets = listOf()
    ruleSetFiles = files("config/pmd/ruleset.xml")
    isIgnoreFailures = true
    isConsoleOutput = false
    sourceSets = emptyList() // keeps pmdMain and pmdTest out of `check`
}

tasks.withType<Pmd>().configureEach {
    // The ANTLR-generated parser is not dampf's code; CParserBase is vendored from grammars-v4.
    exclude("dampf/parser/CLexer.java", "dampf/parser/CParser.java", "dampf/parser/CParser*Visitor.java",
        "dampf/parser/CParserBase.java")
    reports { html.required = true; xml.required = true }
}

// Copied code (PMD's CPD): ./gradlew cpd, report in build/reports/cpd/main.txt.
// -PcpdTokens=<N> sets the smallest duplicate reported (default 100 tokens).
val cpdTool = configurations.create("cpdTool")
dependencies { cpdTool("net.sourceforge.pmd:pmd-cli:7.29.0"); cpdTool("net.sourceforge.pmd:pmd-java:7.29.0") }

tasks.register<JavaExec>("cpd") {
    group = "verification"
    description = "Finds copied code in src/main/java with PMD's CPD"
    classpath = cpdTool
    mainClass = "net.sourceforge.pmd.cli.PmdCli"
    val report = layout.buildDirectory.file("reports/cpd/main.txt")
    outputs.file(report)
    val tokens = providers.gradleProperty("cpdTokens").getOrElse("100")
    inputs.dir("src/main/java")
    inputs.property("tokens", tokens)
    args("cpd", "--language", "java", "--dir", "src/main/java", "--minimum-tokens", tokens,
        "--format", "text", "--report-file", report.get().asFile.path, "--no-fail-on-violation")
    doFirst { report.get().asFile.parentFile.mkdirs() }
    doLast { println("CPD report: " + report.get().asFile) }
}

// dampf/version.properties carries the version into the jar, for dampf --version.
tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("dampf/version.properties") { expand("version" to version) }
}

application {
    mainClass = "dampf.Main"
    applicationName = "dampf"
}

// dampf's headers next to the launcher (include/dampf.h), for the PC build of a game:
// gcc -idirafter <dampf>/include
distributions {
    main {
        contents {
            from("src/main/resources/dampf/include") {
                into("include")
            }
        }
    }
}

// Records the sources of generated book pages in doc-specs/_sources.lock, read by BookSourcesTest:
// ./gradlew stampBook -Pspecs=doc-specs/guide/introduction.spec.md (several separated by commas, or all)
tasks.register<JavaExec>("stampBook") {
    group = "documentation"
    description = "Records the sources of generated book pages in doc-specs/_sources.lock"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dampf.book.BookSources"
    args((findProperty("specs") as String? ?: "all").split(","))
}
