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

    testImplementation(project(":runtime"))
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":runtime:jar")
}

application {
    mainClass.set("Main")
}
