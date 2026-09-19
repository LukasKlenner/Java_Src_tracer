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

application {
    mainClass.set("srctracer.Main")
}

dependencies {
    implementation(project(":instrumenter"))
    implementation(project(":key-annotater"))
    implementation(project(":shared"))
    implementation("com.code-intelligence:jazzer-api:0.22.1")

    testImplementation(project(":runtime"))
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("run") {
    dependsOn(":runtime:jar")
    dependsOn(":runtime-binary:jar")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":runtime:jar")
    dependsOn(":runtime-binary:jar")
}
