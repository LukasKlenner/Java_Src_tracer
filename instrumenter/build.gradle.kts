plugins {
    `java-library`
    // TODO
    // id("com.gradleup.shadow") version "9.6.1"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.github.javaparser:javaparser-core:3.26.4")
}
