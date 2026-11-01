import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }

// The release key. CI passes it in the environment; a maintainer's machine keeps it in ~/.config/herdr-pocket/signing.properties.
// Without it, builds are debug-signed and can't update, or be updated by, the published releases.
val signing = Properties().apply {
    val local = File(System.getProperty("user.home"), ".config/herdr-pocket/signing.properties")
    if (local.isFile) local.inputStream().use(::load)
    System.getenv("POCKET_SIGNING_STORE")?.let { setProperty("storeFile", it) }
    val password = System.getenv("POCKET_SIGNING_PASSWORD") ?: getProperty("passwordFile")?.let { File(it).takeIf(File::isFile)?.readText()?.trim() }
    password?.let { setProperty("password", it) }
}
// Each commit builds a higher version than the one before it: minutes from 2025 to the commit's time, so an installed
// build always takes the updates published after it. CI and local builds of one commit get the same number.
val commitMinutes = providers.exec { commandLine("git", "log", "-1", "--format=%ct"); isIgnoreExitValue = true }
    .standardOutput.asText.map { ((it.trim().toLongOrNull() ?: 1735689660L) - 1735689600L) / 60 }
android {
    namespace = "dev.herdr.pocket"
    compileSdk = 35
    defaultConfig { applicationId = "dev.herdr.pocket"; minSdk = 26; targetSdk = 35; versionCode = commitMinutes.get().toInt(); versionName = "0.3.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // iroh ships a 9–14 MB library per ABI: phones, and x86_64 for Intel emulators. 32-bit x86 and MIPS phones are long gone.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        // Where installed copies look for updates: latest.json on the newest GitHub release. A fork points this at its own.
        val updates = providers.gradleProperty("pocket.updateUrl").orElse("https://github.com/namastex888/herdr-pocket/releases/latest/download/latest.json")
        buildConfigField("String", "UPDATE_URL", "\"${updates.get()}\"") }
    signingConfigs { if (signing.getProperty("storeFile") != null && signing.getProperty("password") != null) create("pocket") {
        storeFile = file(signing.getProperty("storeFile")); storePassword = signing.getProperty("password")
        keyAlias = signing.getProperty("alias", "pocket"); keyPassword = signing.getProperty("password") } }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        // Debug builds use the release key too when it's here, so a build from Android Studio and the published ones update each other.
        debug { signingConfigs.findByName("pocket")?.let { signingConfig = it } }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("pocket") ?: signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
        // The iroh jar also carries desktop libraries; Android takes its own from iroh-android's jni/.
        "darwin-*/**", "linux-*/**", "win32-*/**")
        // Compressed in the APK: about half the download, unpacked once at install.
        jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.83")
    // iroh: dial a paired desktop by its key; direct when it can, an end-to-end encrypted relay when it can't.
    implementation("computer.iroh:iroh-android:1.2.0")
    // A bundled QR scanner (camera + ZXing): works without Google Play services.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
