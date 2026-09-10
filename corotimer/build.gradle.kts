import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.detekt)
    `maven-publish`
}

group = "com.erkankrcr"
version = "1.0.0"

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Corotimer"
            isStatic = true
        }
    }

    sourceSets {
        // The virtual-time test scheduler API (runTest, TestCoroutineScheduler, backgroundScope)
        // is marked experimental upstream; every E2E test drives the engine through it. Applied to
        // every source set (not just commonTest) because `optIn` on languageSettings does not
        // propagate across `dependsOn` edges to jvmTest/androidUnitTest on this Kotlin version.
        all {
            languageSettings {
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        commonMain.dependencies {
            // `api`, not `implementation`: the public surface returns StateFlow, so
            // consumers must be able to name the type.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
        }
    }
}

android {
    namespace = "com.erkankrcr.corotimer"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom("src")
}

tasks.withType<Detekt>().configureEach {
    exclude("**/build/**")
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jvmTest"))
    dependsOn(tasks.withType<Detekt>())
}

publishing {
    repositories {
        maven {
            name = "localRepo"
            url = layout.buildDirectory.dir("repo").get().asFile.toURI()
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Corotimer")
            description.set(
                "Kotlin Multiplatform countdown timer & stopwatch with a declarative DSL and StateFlow output.",
            )
            url.set("https://github.com/erkankrcr/corotimer")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("erkankrcr")
                    name.set("Erkan Karacar")
                }
            }
            scm {
                url.set("https://github.com/erkankrcr/corotimer")
                connection.set("scm:git:git://github.com/erkankrcr/corotimer.git")
            }
        }
    }
}
