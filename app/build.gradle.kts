plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
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
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    implementation("ai.djl.huggingface:tokenizers:0.29.0")
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    implementation("com.amazonaws:aws-lambda-java-events:3.11.4")
    testImplementation(kotlin("test"))
}

// Replaces the application plugin's run task. Dropping the application plugin avoids
// ShadowApplicationPlugin being auto-applied, which breaks on Gradle 9 (reads the
// removed ApplicationPluginConvention.mainClassName via the convention system).
tasks.register<JavaExec>("run") {
    group       = "application"
    description = "Runs this project as a JVM application"
    mainClass.set("org.horizon.AppKt")
    classpath    = sourceSets["main"].runtimeClasspath
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    manifest {
        attributes["Main-Class"] = "org.horizon.AppKt"
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
