allprojects {
    group = "srctracer"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

dependencies {
    implementation(project(":instrumenter"))
    implementation(project(":key-annotater"))
}

plugins {
    application
}
