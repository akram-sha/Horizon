plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("software.amazon.awssdk:dynamodb:2.25.60")
    implementation("org.slf4j:slf4j-simple:2.0.12")
    testImplementation(kotlin("test"))
}

application {
    mainClass = "org.horizon.AppKt"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}