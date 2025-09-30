import io.ktor.plugin.features.*

val ktor_version: String by project
val exposed_version: String by project
val h2_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.1.10"
    id("io.ktor.plugin") version "3.1.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    jvmToolchain(17)
}




group = "it.sebi"
version = "0.2.6"

application {
    mainClass = "io.ktor.server.netty.EngineMain"

}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("org.openfolder:kotlin-asyncapi-ktor:3.1.1")
    implementation("io.ktor:ktor-server-compression")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-host-common")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-sessions")
    implementation("dev.hayden:khealth:3.0.2")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-crypt:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
//    implementation("org.jetbrains.exposed:exposed-r2dbc:$exposed_version")
//    implementation("org.jetbrains.exposed:exposed-migration-r2dbc:$exposed_version")
//    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
//    implementation("org.jetbrains.exposed:exposed-migration:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-logging")
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("com.h2database:h2:$h2_version")
    implementation("org.postgresql:postgresql:42.5.4")
//    implementation("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-cors:3.1.3")
    implementation("io.ktor:ktor-server-sse:3.1.3")
    // Robust fingerprinting of PDFs
    implementation("org.apache.pdfbox:pdfbox:2.0.31")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("io.mockk:mockk:1.13.5")
    testImplementation("io.ktor:ktor-client-mock")
}


// Task per buildare il frontend
tasks.register("buildFrontend") {
    group = "frontend"
    description = "Builda l'applicazione frontend React"

    doLast {
        exec {
            workingDir("${project.projectDir}/frontend") // adatta il path alla tua struttura
            commandLine("mkdir", "-p", "${project.projectDir}/src/main/resources")
        }
        exec {
            workingDir("${project.projectDir}/frontend") // adatta il path alla tua struttura
            commandLine("npm", "install")
        }

        exec {
            workingDir("${project.projectDir}/frontend") // adatta il path alla tua struttura
            commandLine("npm", "run", "build")
        }
    }
}

// Aggiungi la dipendenza al task buildImage
tasks.named("publishImage") {
    dependsOn("buildFrontend")
}

fun String.toKebabCase(): String {
    return this.replace(Regex("([a-z])([A-Z])")) {
        "${it.groupValues[1]}-${it.groupValues[2].lowercase()}"
    }.lowercase()
}


ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_17)
        localImageName.set("sample-docker-image")
        imageTag.set(rootProject.version.toString())
        environmentVariables.set(listOf(
            DockerEnvironmentVariable("JAVA_OPTS", "-Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -server"),
            DockerEnvironmentVariable("JVM_OPTS", "-Xmx2g -XX:+UseG1GC")
        ))
        portMappings.set(
            listOf(
                DockerPortMapping(
                    8080,
                    8080,
                    DockerPortMappingProtocol.TCP
                )
            )
        )
        externalRegistry.set(
            io.ktor.plugin.features.DockerImageRegistry.dockerHub(
                appName = provider { "StrapiSyncWizard".toKebabCase() },
                username = providers.environmentVariable("DOCKER_HUB_USERNAME"),
                password = providers.environmentVariable("DOCKER_HUB_PASSWORD")
            )
        )
    }

}
