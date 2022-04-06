plugins {
    kotlin("multiplatform") version "1.6.10"
    id("maven-publish")
}

group = "io.github.versi.kurl"
version = "0.0.2"

repositories {
    mavenCentral()
}

publishing {
    repositories {
        maven {
            name = "nexusReleases"
            credentials {
                val nexusUser: String? by project
                val nexusPassword: String? by project
                username = System.getenv("NEXUS_USERNAME") ?: System.getenv("NEXUS_USER") ?: nexusUser
                password = System.getenv("NEXUS_PASSWORD") ?: System.getenv("NEXUS_PASS") ?: nexusPassword
            }
            url = uri(System.getenv("NEXUS_URL") ?: "")
        }
    }
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    js(BOTH) {
        browser {
            commonWebpackConfig {
                cssSupport.enabled = true
            }
        }
    }
    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        compilations.getByName("main") {
            cinterops {
                val libcurl by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libcurl.def"))
                    packageName("io.github.versi.kurl.curl")
                    compilerOpts("-I/path")
                    includeDirs.allHeaders("path")
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting
        val jvmTest by getting
        val jsMain by getting
        val jsTest by getting
        val nativeMain by getting
        val nativeTest by getting
    }
}
