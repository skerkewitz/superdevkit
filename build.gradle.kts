// The version of the whole kit (ADR record 26): milestone N is 0.N.0. dampf --version and
// superdevkit --version print it. `./gradlew installDist` writes the distribution in the shape of a
// release (ADR record 41) to build/install/superdevkit: bin/ (dampf, superdevkit), java/, include/.
allprojects {
    group = "superdevkit"
    version = "0.7.1"
    repositories {
        mavenCentral()
    }
}
