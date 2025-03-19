plugins {
    kotlin("jvm") version "2.1.0"
    id("io.ktor.plugin") version "3.0.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

group = "org.nous"
version = "0.1"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.1.0"
val ktorVersion = "3.0.3"

dependencies {
    testImplementation(kotlin("test"))
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // yaml support for kotlinx.serialization
    implementation("com.charleskorn.kaml:kaml:0.65.0")

    // fast b-tree file backed local database (for development or small deployments)
    implementation("org.mapdb:mapdb:3.1.0")
    // dynamically scan for serializable types for the websocket UserInteraction implementation
    implementation("io.github.classgraph:classgraph:4.8.179")

    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}