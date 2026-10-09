plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.serialization) }
android {
    namespace = "com.monostr.nostr"; compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions.unitTests.all { it.useJUnitPlatform() }
    testOptions.unitTests.isReturnDefaultValues = true // android.util.Log in NostrEngine is a no-op on the JVM
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":tips"))
    implementation(libs.rustnostr.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.rustnostr.jvm)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
