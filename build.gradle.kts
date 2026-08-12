allprojects {
    group = "srctracer"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":instrumenter"))
    implementation(project(":key-annotater"))
    implementation(project(":shared"))
}

application {
    mainClass.set("Main")
}
