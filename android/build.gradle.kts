plugins {
    id("com.android.application")
    kotlin("android")
}

val gdxVersion: String by rootProject.extra
val natives: Configuration by configurations.creating

// Release-Keystore (optional): KEYSTORE_FILE + KEYSTORE_PASS + KEY_ALIAS + KEY_PASS
// Fehlt er, wird der Release-Build mit dem Debug-Key signiert -> APK ist trotzdem installierbar.
val releaseKeystore: File? = System.getenv("KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "dev.corebreach"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.corebreach"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = System.getenv("GITHUB_REF_NAME")
            ?.takeIf { it.startsWith("v") }
            ?.removePrefix("v")
            ?: "0.1.0-dev"
    }

    // Kotlin-Quellen liegen in src/main/java (Default, wird von AGP + Kotlin-Plugin automatisch gebaut)
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        res.srcDirs("res")
        assets.srcDirs("../assets")
        jniLibs.srcDirs("libs")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASS")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")

    listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").forEach { abi ->
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-$abi")
        natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-$abi")
    }
}

// libGDX-Natives (.so) aus den natives-*.jar nach android/libs/<abi>/ entpacken.
// Bewusst OHNE deklarierte inputs/outputs (wie im offiziellen libGDX-Liftoff-Template):
// ein deklarierter Output auf android/libs loest sonst Gradles strenge
// "implicit dependency"-Validierung bei allen AGP-Tasks aus, die die jniLibs lesen.
val copyAndroidNatives by tasks.registering {
    group = "build"
    description = "Entpackt die libGDX/Box2D-Natives nach android/libs/<abi>/"
    doFirst {
        val abis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        natives.files.forEach { jar ->
            val abi = jar.nameWithoutExtension.substringAfterLast("natives-")
            check(abi in abis) { "Unerwartetes Native-Jar: ${jar.name}" }
            val outDir = file("libs/$abi")
            outDir.mkdirs()
            copy {
                from(zipTree(jar))
                into(outDir)
                include("*.so")
            }
        }
        val count = fileTree("libs").matching { include("**/*.so") }.files.size
        check(count >= 8) { "Nur $count .so-Dateien entpackt, erwartet: 8 (gdx + box2d x 4 ABIs)" }
        logger.lifecycle(">> CORE//BREACH: $count native libs bereit")
    }
}

// Natives muessen VOR jedem Build-Schritt liegen, nicht erst beim Mergen
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyAndroidNatives) }
tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(copyAndroidNatives) }
