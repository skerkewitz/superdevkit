plugins {
    application
    antlr
}

group = "dampf"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}


dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
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
    // Tests that need the Blubber Blasen sources read their location from this property.
    providers.gradleProperty("blubberblasen").orNull?.let { systemProperty("dampf.blubberblasen", it) }
}

application {
    mainClass = "dampf.Main"
    applicationName = "dampf"
}
