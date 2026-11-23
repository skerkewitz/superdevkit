import java.time.Duration

plugins {
    application
    antlr
    jacoco
    pmd
}

// dampf, superdevkit's compiler, with its emulator and the tests of the whole kit (the version is
// the root project's, ADR-6F3CJT1P). The tests and stampBook run in the repository's top folder,
// where the paths they name begin.


val coverage = providers.gradleProperty("coverage").isPresent

// The antlr plugin puts the ANTLR tool (and ICU, StringTemplate) on api, so it would ship in every
// distribution; dampf needs only the runtime when it runs.
configurations.api { setExtendsFrom(extendsFrom.filter { it.name != "antlr" }) }

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2") // reads the CPU test suite
    testImplementation(project(":cli"))   // the tests build the examples as superdevkit build does
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
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
    // `./gradlew test --tests X` runs in both subprojects; X is in one of them
    filter { isFailOnNoMatchingTests = false }
    maxHeapSize = "2g"
    // Test classes run in parallel JVMs, one per core and at most 4, since each may take 2 GB
    // (-PtestForks=n sets the number). A class that writes files writes them where no other class
    // does, and the conversions into an example's build/gen write atomically (docs/testing.md).
    maxParallelForks = providers.gradleProperty("testForks").orNull?.toInt()
        ?: Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    // a last resort: the program tests have their own limits (a hang fails the test, see ExecutionTest)
    timeout.set(Duration.ofHours(2))
    // dampf's headers from the sources (SystemHeaders), so that the paths in the output are the
    // same on every run and machine
    systemProperty("dampf.include", "compiler/src/main/resources/dampf/include")
    // the installed superdevkit command (the BRR Lab and the desktop tests run it)
    dependsOn(":cli:installDist")
    // Files the tests read that are not on the class path: without them as inputs, a change to
    // a document, the game's snapshot or the platform layer left the task up to date, and
    // BookSourcesTest, AdrTest and the game's tests did not run again.
    inputs.files(
        rootProject.fileTree("docs") { exclude("book/node_modules/**", "book/.vitepress/cache/**", "book/.vitepress/dist/**") },
        rootProject.fileTree("doc-specs"), rootProject.fileTree("examples") { exclude("*/build/**") },
        rootProject.fileTree("reference"), rootProject.fileTree("tools"), rootProject.fileTree("dev"),
        rootProject.fileTree("lib"), rootProject.file("README.md"), rootProject.file("CLAUDE.md"),
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("documentsAndExamples")
    // Execution tests run ROMs in Mesen too (MesenCE, ADR-6F3CK1V6): -Pmesen=<path>, or the one in
    // .mesen/ (dev/fetch_mesen.sh), the Linux build before the Windows one
    val mesen = providers.gradleProperty("mesen").orNull
        ?: listOf(".mesen/Mesen", ".mesen/Mesen.exe").map { rootProject.layout.projectDirectory.file(it).asFile }
            .firstOrNull { it.exists() }?.path
    mesen?.let { systemProperty("dampf.mesen", it) }
    // Random programs: -Prandom=<count> (default 50), -PrandomSeed=<first seed>
    providers.gradleProperty("random").orNull?.let { systemProperty("dampf.random.count", it) }
    providers.gradleProperty("randomSeed").orNull?.let { systemProperty("dampf.random.seed", it) }
    // RandomMacrosTest: -PppRandom=N macro programs from -PppSeed=S (default 300 from 1)
    providers.gradleProperty("ppRandom").orNull?.let { systemProperty("pp.random", it) }
    providers.gradleProperty("ppSeed").orNull?.let { systemProperty("pp.seed", it) }
    // At most n pass applications (dampf.util.PassLimit, dev/bisect_passes.sh): -PoptLimit=n or
    // DAMPF_OPT_LIMIT; the last one is appended to the file DAMPF_OPT_LIMIT_LOG names. As system
    // properties they are inputs of the task, so a new limit runs the tests again.
    (providers.gradleProperty("optLimit").orNull ?: providers.environmentVariable("DAMPF_OPT_LIMIT").orNull)
        ?.let { systemProperty("dampf.optLimit", it) }
    providers.environmentVariable("DAMPF_OPT_LIMIT_LOG").orNull?.let { systemProperty("dampf.optLimit.log", it) }
    // Only the execution test of one program: -Pexec=<name without .c>
    providers.gradleProperty("exec").orNull?.let { systemProperty("dampf.exec.only", it) }
    // Records new hashes of the back end's output (BackendOutputTest): -PbackendOutput=update
    providers.gradleProperty("backendOutput").orNull?.let { systemProperty("dampf.backendOutput", it) }
    // The 65816 emulator against the SingleStepTests suite (downloads ~50 MB once): -PcpuTests
    providers.gradleProperty("cpuTests").orNull?.let { systemProperty("dampf.cpuTests", it.ifEmpty { "300" }) }
    // Code coverage (JaCoCo): -Pcoverage, report in build/reports/jacoco/test/html/index.html.
    // Off by default: the agent slows down the tests that run the emulator.
    extensions.configure<JacocoTaskExtension> { isEnabled = coverage }
    if (coverage) finalizedBy(tasks.jacocoTestReport)
}

// The class path of the tests in a file, for dev/random_soak.sh, which runs several shares of the
// random programs at once as plain Java processes (dampf.exec.RandomSoak)
tasks.register("soakClasspath") {
    dependsOn(tasks.testClasses)
    val classpath = sourceSets.test.get().runtimeClasspath
    val out = layout.buildDirectory.file("soak/classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
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
    ruleSetFiles = rootProject.files("config/pmd/ruleset.xml")
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
    // dampf's headers in the distribution's include/ (SystemHeaders): the start scripts put the
    // installation's folder in place of the marker
    applicationDefaultJvmArgs = listOf("-Ddampf.include=__APP_HOME__/include")
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
    workingDir = rootProject.projectDir
    args((findProperty("specs") as String? ?: "all").split(","))
}

// Lists the generated book pages older than their sources and fails if there are any. Not part
// of `build`: the book is brought in step before a release (ADR-6F60ZB4N).
tasks.register<JavaExec>("checkBook") {
    group = "verification"
    description = "Fails when a generated book page is older than its spec or sources"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dampf.book.BookSources"
    workingDir = rootProject.projectDir
    args("check")
}

// The jar is dampf's, whatever the subproject is called
tasks.jar { archiveBaseName = "dampf" }
