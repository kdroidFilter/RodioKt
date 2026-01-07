import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import gobley.gradle.GobleyHost
import gobley.gradle.Variant
import gobley.gradle.cargo.dsl.jvm
import gobley.gradle.cargo.tasks.CargoBuildTask
import gobley.gradle.cargo.tasks.FindDynamicLibrariesTask
import gobley.gradle.cargo.tasks.RustUpTargetAddTask
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

fun rustLibraryName(triple: String): String = when {
    triple.contains("windows") -> "souvlaki_kt.dll"
    triple.contains("darwin") || triple.contains("apple") -> "libsouvlaki_kt.dylib"
    else -> "libsouvlaki_kt.so"
}

fun Project.prebuiltRustLibrary(triple: String): File =
    layout.projectDirectory.dir("target/$triple/release").file(rustLibraryName(triple)).asFile

// Only these targets are built in CI - skip others to avoid cross-compilation errors
val supportedTargets = setOf(
    "aarch64-apple-darwin",
    "x86_64-apple-darwin",
    "x86_64-unknown-linux-gnu",
    "aarch64-unknown-linux-gnu",
    "x86_64-pc-windows-msvc",
    "aarch64-pc-windows-msvc"
)

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinAtomicfu)
    alias(libs.plugins.gobleyCargo)
    alias(libs.plugins.gobleyRust)
    alias(libs.plugins.gobleyUniffi)
    alias(libs.plugins.maven.publish)
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
    }
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        resources.srcDir("src/main/resources")
    }
}

dependencies {
    implementation(libs.jna)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<ProcessResources>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<CargoBuildTask>().configureEach {
    onlyIf {
        val rustTarget = target.orNull ?: return@onlyIf true
        val triple = rustTarget.rustTriple
        // Skip unsupported targets entirely
        if (triple !in supportedTargets) return@onlyIf false
        val prebuiltLib = project.prebuiltRustLibrary(triple)
        val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
        !prebuiltLib.exists() || isHostTarget
    }
}

tasks.withType<FindDynamicLibrariesTask>().configureEach {
    val rustTarget = rustTarget.orNull ?: return@configureEach
    val triple = rustTarget.rustTriple
    val prebuiltLib = project.prebuiltRustLibrary(triple)
    val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
    if (prebuiltLib.exists() && !isHostTarget) {
        searchPaths.set(listOf(prebuiltLib.parentFile))
    }
}

tasks.withType<RustUpTargetAddTask>().configureEach {
    onlyIf {
        val rustTarget = rustTarget.orNull ?: return@onlyIf true
        val triple = rustTarget.rustTriple
        // Skip unsupported targets entirely
        if (triple !in supportedTargets) return@onlyIf false
        val prebuiltLib = project.prebuiltRustLibrary(triple)
        val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
        !prebuiltLib.exists() || isHostTarget
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral()
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
    coordinates(artifactId = "souvlaki")
    pom {
        name.set("SouvlakiKt")
        description.set("Kotlin media controls library powered by Rust Souvlaki")
    }
}

// Publish native runtime JARs as additional artifacts
afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            if (name == "maven") {
                // Add native runtime JARs for each platform
                val nativeJars = layout.buildDirectory.dir("libs").get().asFile.listFiles()
                    ?.filter { it.name.startsWith("souvlaki-") && it.name.endsWith(".jar") && !it.name.contains("sources") && !it.name.contains("javadoc") }
                    ?: emptyList()

                nativeJars.forEach { jar ->
                    val classifier = jar.name.removePrefix("souvlaki-").removeSuffix(".jar")
                    artifact(jar) {
                        this.classifier = classifier
                    }
                }
            }
        }
    }
}
