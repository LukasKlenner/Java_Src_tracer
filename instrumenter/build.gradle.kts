plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("com.github.javaparser:javaparser-core:3.26.4")
}
