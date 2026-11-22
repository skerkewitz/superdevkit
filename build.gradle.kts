// The version of the whole kit (ADR-6F3CJT1P): milestone N is 0.N.0. dampf --version and
// superdevkit --version print it. `./gradlew installDist` writes the distribution in the shape of a
// release (ADR-6F60Z9QJ) to build/install/superdevkit: bin/ (dampf, superdevkit), java/, include/.
allprojects {
    group = "superdevkit"
    version = "0.8.0"
    repositories {
        mavenCentral()
    }
}
