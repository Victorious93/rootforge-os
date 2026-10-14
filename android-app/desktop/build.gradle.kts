import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets.getByName("main").kotlin {
        srcDir("src/main/kotlin")
        srcDir("../app/src/main/kotlin/ai/rootforge/os/ui")
        exclude("**/Previews.kt")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "ai.rootforge.os.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "RootForgeOS"
            packageVersion = "0.1.0"
            description = "RootForge OS desktop toolkit — ADB, fastboot, EDL via host binaries"
            copyright = "Victorious Framework — Origin Source Labs"
            vendor = "Origin Source Labs"
            linux {
                debMaintainer = "victorious93"
                menuGroup = "Development"
                shortcut = true
                appRelease = "1"
            }
            windows {
                menuGroup = "RootForge OS"
                upgradeUuid = "9f2c4e1a-7b83-4d6e-a1c0-5e8f3b2d9a70"
                shortcut = true
            }
        }
    }
}

tasks.processResources {
    from(rootProject.file("../docker/web")) {
        into("web")
        include("index.html")
    }
}
