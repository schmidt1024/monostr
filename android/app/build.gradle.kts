plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose); alias(libs.plugins.ksp); alias(libs.plugins.hilt); alias(libs.plugins.kotlin.serialization) }

// version from android/version.properties, bumped by hand per release and tagged vX.Y.Z: the same in every
// checkout (shallow clones, the public export, F-Droid's builder), unlike the git history it was derived from up to 0.13.1
val versionProps: Map<String, String> = providers.fileContents(rootProject.layout.projectDirectory.file("version.properties")).asText.get()
    .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
val appVersionCode = versionProps.getValue("versionCode").toInt()
val appVersionName = versionProps.getValue("versionName")
// signing only when the four MONOSTR_* gradle properties exist (~/.gradle/gradle.properties, never in the repo)
val releaseStoreFile = providers.gradleProperty("MONOSTR_STORE_FILE").orNull
// public app values from gradle.properties (spec 4/5); an empty value hides the section that shows it
fun appValue(name: String): String = providers.gradleProperty(name).orNull.orEmpty().replace("\"", "")

android {
    namespace = "com.monostr.app"; compileSdk = 37
    defaultConfig {
        applicationId = "com.monostr.app"; minSdk = 26; targetSdk = 36; versionCode = appVersionCode; versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPPORT_ADDRESS", "\"${appValue("MONOSTR_SUPPORT_ADDRESS")}\"")
        buildConfigField("String", "FEEDBACK_NPUB", "\"${appValue("MONOSTR_FEEDBACK_NPUB")}\"")
        buildConfigField("String", "REPO_URL", "\"${appValue("MONOSTR_REPO_URL")}\"")
        buildConfigField("String", "LICENSE", "\"${appValue("MONOSTR_LICENSE")}\"")
        // spec 5: true until 1.0
        buildConfigField("boolean", "BETA", "true")
    }
    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = providers.gradleProperty("MONOSTR_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("MONOSTR_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("MONOSTR_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // phones only: the JNA/rust-nostr AARs also ship x86/x86_64/mips/armeabi, ~12 MB nobody installs on a phone
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            // reproducible builds: no commit hash of the (private) checkout in META-INF
            vcsInfo.include = false
        }
    }
    // the dependency list AGP would add to the APK signing block is encrypted for Google Play; F-Droid flags it as opaque
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    testOptions.unitTests.all { it.useJUnitPlatform() }
    packaging { resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md") }
    androidResources {
        // spec 3.1: the eight app languages; AGP also writes locales_config.xml for the per-app language picker (Android 13+)
        localeFilters += setOf("en", "de", "es", "ru", "tr", "pt", "fr", "it")
        generateLocaleConfig = true
    }
    lint {
        error += setOf("MissingTranslation", "ExtraTranslation")
        abortOnError = true
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
// Plan 10d: the support screen's contributor list, one name per line (most commits first). Run by hand before a release.
val contributorsFile = layout.projectDirectory.file("src/main/assets/contributors.txt")
val shortlog = providers.exec { commandLine("git", "shortlog", "-sn", "--no-merges", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText
tasks.register("updateContributors") {
    group = "monostr"
    description = "Writes src/main/assets/contributors.txt from git shortlog -sn --no-merges"
    val log = shortlog
    inputs.property("shortlog", log)
    val out = contributorsFile.asFile
    outputs.file(out)
    doLast {
        val names = log.get().lines().map { it.substringAfter('\t').trim() }.filter { it.isNotEmpty() }.distinct()
        out.writeText(names.joinToString("\n", postfix = "\n"))
    }
}
dependencies {
    implementation(project(":nostr"))
    implementation(project(":tips"))
    // DmSync handles gift-wrap Events from DmRepository directly; same artifact :nostr already ships
    implementation(libs.rustnostr.android)
    implementation(platform(libs.compose.bom)); implementation(libs.compose.material3); implementation(libs.compose.ui); implementation(libs.compose.ui.tooling.preview); implementation(libs.compose.material.icons)
    implementation(libs.activity.compose); implementation(libs.navigation.compose); implementation(libs.lifecycle.runtime.compose); implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler); implementation(libs.hilt.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose); implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.core); implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.work.runtime); implementation(libs.core.ktx); implementation(libs.zxing.core); implementation(libs.core.splashscreen)
    implementation(libs.media3.exoplayer); implementation(libs.media3.ui)
    testImplementation(libs.rustnostr.jvm); testImplementation(libs.junit.jupiter); testImplementation(libs.kotlinx.coroutines.test); testImplementation(libs.turbine); testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation(libs.compose.ui.test.junit4); androidTestImplementation(libs.androidx.test.runner); androidTestImplementation(libs.androidx.test.ext.junit); androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.intents); androidTestImplementation(libs.rustnostr.android)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.compose.ui.test.manifest)
}
