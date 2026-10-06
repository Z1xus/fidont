import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.sqldelight.runtime)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite)
        }
    }
}

sqldelight {
    databases {
        create("Database") {
            packageName = "us.z1x.fidont.store"
        }
    }
}

tasks.register<Exec>("conformance") {
    val classpath = kotlin.jvm().compilations["test"].run { output.allOutputs + runtimeDependencyFiles }
    inputs.files(classpath)
    environment("CLASSPATH", classpath.asPath)
    commandLine("uv", "run", "conformance.py")
}
