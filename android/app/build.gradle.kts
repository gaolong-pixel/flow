import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is intentionally loaded from a user-level file so secrets never
// enter the repository. The file is expected at:
//   ~/.android/keystores/flow-release.properties
// Debug builds do not require this file.
val releaseSigningPropertiesFile = File(
    System.getProperty("user.home"),
    ".android/keystores/flow-release.properties"
)
val releaseSigningProperties = Properties()
var releaseSigningIssue: String? = null
var releaseSigningStoreFile: java.io.File? = null

if (!releaseSigningPropertiesFile.isFile) {
    releaseSigningIssue =
        "Release signing config not found at ${releaseSigningPropertiesFile.absolutePath}."
} else {
    try {
        releaseSigningPropertiesFile.inputStream().use { input ->
            releaseSigningProperties.load(input)
        }
        val requiredFields = listOf(
            "storeFile",
            "storePassword",
            "keyAlias",
            "keyPassword"
        )
        val missingFields = requiredFields.filter {
            releaseSigningProperties.getProperty(it).isNullOrBlank()
        }
        if (missingFields.isNotEmpty()) {
            releaseSigningIssue =
                "Release signing config is incomplete: missing ${missingFields.joinToString(", ")}."
        } else {
            val rawStoreFile = releaseSigningProperties.getProperty("storeFile").trim()
            val candidateStoreFile = File(rawStoreFile).let { file ->
                if (file.isAbsolute) file else releaseSigningPropertiesFile.parentFile.resolve(rawStoreFile)
            }
            if (!candidateStoreFile.isFile || !candidateStoreFile.canRead()) {
                releaseSigningIssue =
                    "Release keystore not found or not readable at ${candidateStoreFile.absolutePath}."
            } else {
                releaseSigningStoreFile = candidateStoreFile
            }
        }
    } catch (_: Exception) {
        // Do not include parser/property contents in the error: the file contains secrets.
        releaseSigningIssue =
            "Release signing config could not be read or parsed: ${releaseSigningPropertiesFile.absolutePath}."
    }
}

val releaseSigningReady = releaseSigningIssue == null && releaseSigningStoreFile != null

android {
    namespace = "com.flow.app"
    compileSdk = 33
    buildToolsVersion = "33.0.2"

    defaultConfig {
        applicationId = "com.flow.app"
        minSdk = 29
        targetSdk = 33
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseSigningStoreFile
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.8"
    }

    testOptions {
        unitTests.all {
            it.systemProperty(
                "flow.sharedSpecFile",
                rootProject.file("../shared-spec/parser-cases.json").absolutePath
            )
        }
    }

    // The Phase 1 V0 sources under src/main/java are kept for reference only.
    // The runnable Phase 2 app lives in src/main/kotlin and is the only source set.
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/main/kotlin"))
            kotlin.setSrcDirs(listOf("src/main/kotlin"))
        }
        getByName("test") {
            java.setSrcDirs(listOf("src/test/kotlin"))
            kotlin.setSrcDirs(listOf("src/test/kotlin"))
        }
    }
}

// Fail only when a Release task is actually requested. This keeps Debug builds
// and ordinary Gradle configuration working on machines without the signing file.
if (!releaseSigningReady) {
    gradle.taskGraph.whenReady {
        val releaseTasks = gradle.taskGraph.allTasks.filter { task ->
            task.project == project && task.name.contains("Release")
        }
        if (releaseTasks.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "无法构建 Release：${releaseSigningIssue ?: "Release 签名配置不可用。"} " +
                    "请在 ${releaseSigningPropertiesFile.absolutePath} 配置正式签名后再构建。Debug 构建不依赖该文件。"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.06.01"))
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
