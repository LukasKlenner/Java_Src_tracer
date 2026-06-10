plugins {
    application
    id("com.gradleup.shadow") version "8.3.5"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("com.github.javaparser:javaparser-core:3.26.4")
}

application {
    mainClass.set("srctracer.instrumenter.Main")
}
