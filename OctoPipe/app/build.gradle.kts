plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace="com.octopus.octopipe"
 compileSdk=36
 defaultConfig { applicationId="com.octopus.octopipe"; minSdk=26; targetSdk=35; versionCode=4; versionName="0.4.0"; vectorDrawables { useSupportLibrary=true } }
 buildTypes { debug { applicationIdSuffix=".debug" }; release { isMinifyEnabled=false } }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 buildFeatures { compose=true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.documentfile:documentfile:1.0.1")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
 implementation("androidx.media3:media3-ui:1.5.1")
 implementation("androidx.media3:media3-session:1.5.1")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("io.coil-kt:coil-compose:2.7.0")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
