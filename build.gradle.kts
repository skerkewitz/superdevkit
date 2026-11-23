// The version of the whole kit (ADR-6F3CJT1P): milestone N is 0.N.0. dampf --version and
// superdevkit --version print it. `./gradlew installDist` writes the distribution in the shape of a
// release (ADR-6F60Z9QJ) to build/install/superdevkit: bin/ (dampf, superdevkit), java/, include/.
// `./gradlew releaseZip` adds the Java runtime and raylib and writes the zip of this system.
allprojects {
    group = "superdevkit"
    version = "0.9.2"
    repositories {
        mavenCentral()
    }
}

// Both launchers find the installation's folder, the jars in java/ (lib/ is the kit's library in a
// release, ADR-6F60Z9QJ), and the release's own Java in jre/ before any Java of the machine.
subprojects {
    tasks.withType<CreateStartScripts>().configureEach {
        doLast {
            fun String.swap(old: String, new: String): String {
                check(old in this) { "the start script no longer has: $old" }
                return replace(old, new)
            }
            unixScript.writeText(unixScript.readText()
                .swap("__APP_HOME__", "'\"\$APP_HOME\"'")
                .swap("\$APP_HOME/lib/", "\$APP_HOME/java/")
                .swap("if [ -n \"\$JAVA_HOME\" ] ; then",
                    "if [ -x \"\$APP_HOME/jre/bin/java\" ] ; then\n" +
                        "    JAVACMD=\$APP_HOME/jre/bin/java\n" +
                        "elif [ -n \"\$JAVA_HOME\" ] ; then"))
            windowsScript.writeText(windowsScript.readText()
                .swap("__APP_HOME__", "%APP_HOME%")
                .swap("%APP_HOME%\\lib\\", "%APP_HOME%\\java\\")
                .swap("if defined JAVA_HOME goto findJavaFromJavaHome",
                    "set JAVA_EXE=%APP_HOME%\\jre\\bin\\java.exe\r\n" +
                        "if exist \"%JAVA_EXE%\" goto execute\r\n\r\n" +
                        "if defined JAVA_HOME goto findJavaFromJavaHome"))
        }
    }
}
