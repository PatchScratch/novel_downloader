plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

val appVersion: String = run {
    val prop = (project.findProperty("appVersion") as String?)?.removePrefix("v")?.trim()
    if (!prop.isNullOrEmpty()) return@run prop
    val pyFile = rootProject.projectDir.parentFile.resolve("novel_downloader.py")
    if (pyFile.exists()) {
        Regex("""__version__\\s*=\\s*[\"']([^\"']+)[\"']""")
            .find(pyFile.readText())?.groupValues?.get(1)?.let { return@run it }
    }
    "0.0.0"
}
val appVerParts: List<Int> =
    (appVersion.split(".") + listOf("0", "0", "0")).take(3).map { it.toIntOrNull() ?: 0 }

fun signingSecret(env: String, prop: String): String? =
    (System.getenv(env) ?: project.findProperty(prop) as String?)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStorePath = signingSecret("NOVEL_KEYSTORE", "novelStoreFile")
val releaseStorePassword = signingSecret("NOVEL_KEYSTORE_PASSWORD", "novelStorePassword")
val releaseKeyAlias = signingSecret("NOVEL_KEY_ALIAS", "novelKeyAlias")
val releaseKeyPassword =
    signingSecret("NOVEL_KEY_PASSWORD", "novelKeyPassword") ?: releaseStorePassword
val hasReleaseSigning =
    releaseStorePath != null && releaseStorePassword != null && releaseKeyAlias != null

android {
    namespace = "com.ayati.noveldownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ayati.noveldownloader"
        minSdk = 24
        targetSdk = 35
        versionCode = appVerParts[0] * 10000 + appVerParts[1] * 100 + appVerParts[2]
        versionName = appVersion

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile =
                    file(releaseStorePath!!.replaceFirst("~", System.getProperty("user.home")))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

chaquopy {
    defaultConfig {
        version = "3.12"
        val overridePy = System.getenv("NOVEL_BUILD_PYTHON")?.trim().orEmpty()
        if (overridePy.isNotEmpty()) {
            buildPython(overridePy)
        } else {
            buildPython("/usr/bin/python3")
        }
        pip {
            install("requests")
            install("beautifulsoup4")
            install("Pillow")
        }
    }
}

val syncNovelDownloader by tasks.registering(Copy::class) {
    from("../../novel_downloader.py")
    into("src/main/python")
}

val syncCoverFont by tasks.registering(Copy::class) {
    from("../../font/AyatiShowaSerif-Regular.ttf")
    into("src/main/assets/fonts")
}

tasks.named("preBuild") {
    dependsOn(syncNovelDownloader, syncCoverFont)
}

tasks.matching {
    it.name.contains("PythonSources") || it.name.contains("Assets")
}.configureEach {
    dependsOn(syncNovelDownloader, syncCoverFont)
}
