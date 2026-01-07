import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":rodio"))
            implementation(project(":souvlaki"))
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }



    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            modules("jdk.accessibility")

            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "sample"
            packageVersion = "1.0.0"
        }
    }
}
