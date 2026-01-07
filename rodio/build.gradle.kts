import com.vanniktech.maven.publish.KotlinMultiplatform
import gobley.gradle.GobleyHost
import gobley.gradle.Variant
import gobley.gradle.cargo.dsl.jvm
import gobley.gradle.cargo.tasks.CargoBuildTask
import gobley.gradle.cargo.tasks.FindDynamicLibrariesTask
import gobley.gradle.cargo.tasks.RustUpTargetAddTask
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import java.io.File

fun rustLibraryName(triple: String): String = when {
    triple.contains("windows") -> "rodio_kt.dll"
    triple.contains("darwin") || triple.contains("apple") -> "librodio_kt.dylib"
    else -> "librodio_kt.so"
}

fun Project.prebuiltRustLibrary(triple: String): File =
    layout.projectDirectory.dir("target/$triple/release").file(rustLibraryName(triple)).asFile

fun Project.latestRustInputTimestamp(): Long {
    val inputs = mutableListOf<File>()
    inputs += file("Cargo.toml")
    inputs += file("Cargo.lock")
    inputs += file("uniffi.toml")
    inputs += fileTree("src/main/rust") { include("**/*") }.files
    return inputs
        .filter { it.exists() }
        .maxOfOrNull { it.lastModified() }
        ?: 0L
}

fun Project.isPrebuiltRustLibraryFresh(triple: String): Boolean {
    val prebuilt = prebuiltRustLibrary(triple)
    if (!prebuilt.exists()) return false
    return prebuilt.lastModified() >= latestRustInputTimestamp()
}

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlinAtomicfu)
    alias(libs.plugins.gobleyCargo)
    alias(libs.plugins.gobleyRust)
    alias(libs.plugins.gobleyUniffi)
}

cargo {
    jvmVariant.set(Variant.Release)
    builds.jvm {
        // Only embed for the current host platform; other platforms are packed as resources.
        embedRustLibrary = GobleyHost.current.rustTarget == rustTarget
    }
}

rust {
    val userHome = System.getProperty("user.home")
    val cargoBin = file("$userHome/.cargo/bin")
    val rustupToolchainBin = file(
        "$userHome/.rustup/toolchains/stable-${GobleyHost.current.rustTarget.rustTriple}/bin",
    )
    when {
        cargoBin.resolve("rustc").exists() -> toolchainDirectory.set(cargoBin)
        rustupToolchainBin.resolve("rustc").exists() -> toolchainDirectory.set(rustupToolchainBin)
    }
}

uniffi {
    generateFromLibrary {
        build.set(GobleyHost.current.rustTarget)
//        build.set(RustAppleMobileTarget.IosX64)
//        build.set(RustAppleMobileTarget.IosArm64)
//        build.set(RustAppleMobileTarget.IosSimulatorArm64)
    }
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.jna)
        }

        jvmMain {
            resources.srcDir("src/jvmMain/resources")
        }


    }

}

tasks.withType<CargoBuildTask>().configureEach {
    onlyIf {
        val rustTarget = target.orNull ?: return@onlyIf true
        val triple = rustTarget.rustTriple
        val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
        !project.isPrebuiltRustLibraryFresh(triple) || isHostTarget
    }
}

tasks.withType<FindDynamicLibrariesTask>().configureEach {
    val rustTarget = rustTarget.orNull ?: return@configureEach
    val triple = rustTarget.rustTriple
    val prebuiltLib = project.prebuiltRustLibrary(triple)
    val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
    if (project.isPrebuiltRustLibraryFresh(triple) && !isHostTarget) {
        searchPaths.set(listOf(prebuiltLib.parentFile))
    }
}

tasks.withType<RustUpTargetAddTask>().configureEach {
    onlyIf {
        val rustTarget = rustTarget.orNull ?: return@onlyIf true
        val triple = rustTarget.rustTriple
        val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
        !project.isPrebuiltRustLibraryFresh(triple) || isHostTarget
    }
}

val hostRuntimeJarTaskName = when (GobleyHost.current.rustTarget?.rustTriple) {
    "aarch64-apple-darwin" -> "jarJvmRustRuntimeMacOSArm64Release"
    "x86_64-apple-darwin" -> "jarJvmRustRuntimeMacOSX64Release"
    "x86_64-unknown-linux-gnu" -> "jarJvmRustRuntimeLinuxX64Release"
    "aarch64-unknown-linux-gnu" -> "jarJvmRustRuntimeLinuxArm64Release"
    "x86_64-pc-windows-msvc" -> "jarJvmRustRuntimeMinGWX64Release"
    else -> null
}

tasks.withType<Jar>().configureEach {
    if (name.startsWith("jarJvmRustRuntime")) {
        onlyIf {
            val isHostTarget = hostRuntimeJarTaskName == name
            isHostTarget
        }
    }
}

configurations.named("jvmRuntimeElements").configure {
    // Attach any available runtime jar tasks (only the host one will be produced because of the onlyIf above).
    tasks.withType(Jar::class.java).configureEach { jarTask ->
        if (!jarTask.name.startsWith("jarJvmRustRuntime") || !jarTask.name.endsWith("Release")) return@configureEach
        outgoing.artifact(jarTask) {
            jarTask.archiveClassifier.orNull?.let { classifier ->
                if (classifier.isNotBlank()) {
                    this.classifier = classifier
                }
            }
        }
    }
}

//Publishing your Kotlin Multiplatform library to Maven Central
//https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-publish-libraries.html
mavenPublishing {
    configure(KotlinMultiplatform(sourcesJar = true))
    publishToMavenCentral()
    if (project.findProperty("signingInMemoryKey") != null || project.findProperty("signing.keyId") != null) {
        signAllPublications()
    }
    coordinates(artifactId = "rodio")

    pom {
        name.set("RodioKt")
        description.set("Kotlin Multiplatform library")
    }
}

publishing {
    publications.withType(MavenPublication::class.java).configureEach publication@{
        if (name == "jvm") {
            // Remove duplicate artifacts that share the same extension and classifier.
            afterEvaluate {
                val seen = mutableSetOf<Pair<String?, String?>>()
                val artifactSet = this@publication.artifacts
                val duplicates = mutableListOf<MavenArtifact>()
                artifactSet.forEach { artifact ->
                    val key = artifact.classifier to artifact.extension
                    if (!seen.add(key)) {
                        duplicates.add(artifact)
                    }
                }
                artifactSet.removeAll(duplicates.toSet())

                val hasMainJar = artifactSet.any { it.extension == "jar" && it.classifier.isNullOrBlank() }
                if (!hasMainJar) {
                    artifact(tasks.named("jvmJar"))
                }

                val hasSourcesJar = artifactSet.any { it.extension == "jar" && it.classifier == "sources" }
                if (!hasSourcesJar) {
                    artifact(tasks.named("jvmSourcesJar"))
                }
            }
        }
    }
}
