import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "app.receiptshare"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.withType<Test> { useJUnitPlatform() }

kotlin { compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }

// Build the Angular frontend and bundle it into the backend's static resources so a
// plain `./gradlew bootRun` or `./gradlew bootJar` serves the whole app from :8080.
val frontendDir = file("../frontend")
// Angular 17's application builder writes the browser bundle into a "browser" subfolder.
val frontendDistDir = frontendDir.resolve("dist/tally-receipts-frontend/browser")
val stagedFrontendRoot = file("$buildDir/generated/frontend")
val stagedFrontendDir = stagedFrontendRoot.resolve("static")
val npmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) listOf("cmd", "/c", "npm") else listOf("npm")

val installFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the frontend dependencies (npm install) when needed."
    workingDir(frontendDir)
    // npm install (not ci) so node_modules is refreshed in place rather than wiped.
    commandLine(npmCommand + listOf("install", "--no-audit", "--no-fund"))
    inputs.file(frontendDir.resolve("package.json"))
    inputs.file(frontendDir.resolve("package-lock.json"))
    outputs.dir(frontendDir.resolve("node_modules"))
}

val buildFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Angular frontend into dist/."
    dependsOn(installFrontend)
    workingDir(frontendDir)
    commandLine(npmCommand + listOf("run", "build"))
    inputs.dir(frontendDir.resolve("src"))
    inputs.file(frontendDir.resolve("angular.json"))
    inputs.file(frontendDir.resolve("package.json"))
    inputs.file(frontendDir.resolve("package-lock.json"))
    outputs.dir(frontendDistDir)
}

val stageFrontend by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies the built frontend into the backend static resources."
    dependsOn(buildFrontend)
    from(frontendDistDir)
    into(stagedFrontendDir)
}

sourceSets {
    main {
        resources {
            // The "static" subfolder is preserved as a path segment so files land in
            // resources/main/static, which Spring Boot serves at the web root.
            srcDir(stagedFrontendRoot)
        }
    }
}

tasks.named("processResources") { dependsOn(stageFrontend) }
