import java.io.File
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

// Release signing. The upload key and its passwords live in keystore.properties,
// which is git-ignored. Point DATUM_KEYSTORE_PROPERTIES at it, or keep it at
// android/keystore.properties. A relative storeFile is read from the same folder
// as keystore.properties. Without the file, release builds come out unsigned.
val keystoreFile = file(System.getenv("DATUM_KEYSTORE_PROPERTIES") ?: rootProject.file("keystore.properties").path)
val keystoreProps = Properties().apply {
    if (keystoreFile.isFile) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "ca.akaind.datum"
    compileSdk = 36

    defaultConfig {
        applicationId = "ca.akaind.datum"
        minSdk = 26
        targetSdk = 36
        versionCode = 200
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }

    signingConfigs {
        val store = keystoreProps.getProperty("storeFile")
        if (store != null) {
            create("release") {
                storeFile = File(store).let { if (it.isAbsolute) it else File(keystoreFile.parentFile, store) }
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Besides the universal APK, one APK per CPU type for sideloading: a Galaxy Z Fold
    // or any other recent phone takes the arm64-v8a one. Google Play gets the AAB,
    // which AGP will not build with APK splits on, so they are off for bundle tasks.
    val bundling = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    splits {
        abi {
            isEnable = !bundling
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    buildFeatures { buildConfig = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "2g"
                // Where Robolectric downloads Android framework jars; defaults to Maven Central.
                providers.gradleProperty("robolectric.repo").orNull?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            }
        }
    }

    packaging {
        resources.excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
    }
}

/**
 * Copies the web app (index.html, data/, packs/) from the repository root into the
 * APK's assets at build time, so the app always ships the same site as the repo.
 */
abstract class SyncWebAssets @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:Internal
    abstract val siteRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val siteFiles: FileTree
        get() = siteRoot.asFileTree.matching { include(SITE) }

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        fs.sync {
            from(siteRoot) { include(SITE) }
            into(outputDir.dir("site"))
        }
    }

    companion object {
        val SITE = listOf("index.html", "data/**", "packs/**")
    }
}

val syncWebAssets = tasks.register<SyncWebAssets>("syncWebAssets") {
    siteRoot.set(rootProject.layout.projectDirectory.dir(".."))
    outputDir.set(layout.buildDirectory.dir("generated/webAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncWebAssets, SyncWebAssets::outputDir)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity)
    implementation(libs.webkit)
    implementation(libs.car.app)
    implementation(libs.car.app.projected)
    implementation(libs.maplibre)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.org.json)
    testImplementation(libs.car.app.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext)
}
