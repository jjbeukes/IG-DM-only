import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    // AGP 9 has built-in Kotlin support; applying org.jetbrains.kotlin.android as well fails.
    id("com.android.application")
}

/**
 * Release signing. On CI it comes from repository secrets (see .github/workflows/build.yml); locally
 * from a gitignored keystore.properties. Android only installs an update over an app signed with the
 * same key, so a stable key is what lets new versions install without logging out of Instagram.
 */
val releaseSigning: Map<String, String?> = run {
    val props = Properties()
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    fun value(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: props.getProperty(prop)?.takeIf { it.isNotBlank() }
    mapOf(
        "storeFile" to value("RELEASE_STORE_FILE", "storeFile"),
        "storePassword" to value("RELEASE_STORE_PASSWORD", "storePassword"),
        "keyAlias" to value("RELEASE_KEY_ALIAS", "keyAlias"),
        "keyPassword" to value("RELEASE_KEY_PASSWORD", "keyPassword"),
    )
}
val hasReleaseKey = releaseSigning.values.all { it != null } &&
    file(releaseSigning.getValue("storeFile")!!).exists()

android {
    namespace = "com.jjbeukes.dmonly"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jjbeukes.dmonly"
        minSdk = 26
        targetSdk = 37
        // CI passes the run number and tag; local builds are 1 / "local".
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0-local"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile")!!)
                storePassword = releaseSigning["storePassword"]
                keyAlias = releaseSigning["keyAlias"]
                keyPassword = releaseSigning["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            // A WebView and a few hundred lines of Kotlin: nothing worth shrinking.
            isMinifyEnabled = false
            // Without a release key (a fresh clone, or CI before the secrets exist) the debug key
            // keeps the APK installable; such builds can't update each other in place.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Version bumps are a decision, not a build failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("com.google.android.material:material:1.14.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
