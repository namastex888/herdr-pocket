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
// Testing self-update locally: -Ppocket.versionCode=1 builds an "older" copy that a normal build then updates.
val versionCodeOverride = providers.gradleProperty("pocket.versionCode").map { it.toLong() }
// The release version (0.N.0), set by the release workflow on dev: scripts/version.sh, version.properties, both at the
// repository root, one level above this Gradle project: the version is the whole product's.
val appVersion: String = Properties().apply { rootProject.file("../version.properties").inputStream().use(::load) }.getProperty("version")

android {
    namespace = "ai.namastex.automagik"
    // Android 16. Google Play takes new apps and updates only at targetSdk 36 or higher.
    compileSdk = 36
    defaultConfig { applicationId = "ai.namastex.automagik"; minSdk = 26; targetSdk = 36; versionCode = versionCodeOverride.orElse(commitMinutes).get().toInt(); versionName = appVersion; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // iroh ships a 9–14 MB library per ABI: phones, and x86_64 for Intel emulators. 32-bit x86 and MIPS phones are long gone.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
    // Where the app comes from (docs/rfcs/0006-distribution-flavors.md). Both keep the app ID and the release key.
    flavorDimensions += "distribution"
    productFlavors {
        // The GitHub releases (automagik.apk): updates itself from latest.json. Android Studio's default variant.
        create("github") { dimension = "distribution"; isDefault = true
            buildConfigField("boolean", "SELF_UPDATE", "true")
            // Where installed copies look for updates: latest.json on the newest GitHub release. A fork points this at its own.
            val updates = providers.gradleProperty("pocket.updateUrl").orElse("https://github.com/automagik-dev/automagik-pocket/releases/latest/download/latest.json")
            buildConfigField("String", "UPDATE_URL", "\"${updates.get()}\"") }
        // Google Play (an app bundle): Play updates it. No updater code, installer permission or update check;
        // src/play holds no-op hooks and the manifest overlay that removes them.
        create("play") { dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "false") }
    }
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
// What the self-updater needs in the manifest: these permissions and its install-result receivers (UpdateReceiver,
// and any other Update…Receiver). The github variants must keep them; the play variants must have none.
val installerPermissions = listOf("android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
    "android.permission.INSTALL_PACKAGES", "android.permission.REQUEST_DELETE_PACKAGES")
val updaterComponent = """.*\.Update\w*Receiver"""
// What every variant must keep: iroh's native library calls ConnectivityManager (networks, link properties, DNS servers).
val requiredPermissions = listOf("android.permission.ACCESS_NETWORK_STATE")

/** Reads a variant's merged manifest and fails the build when an updater permission or component is wrongly there (or missing). */
abstract class VerifyUpdaterManifest : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    /** True: the variant must declare none of [permissions] and no component matching [component]. False: all of them, and one. */
    @get:Input abstract val forbid: Property<Boolean>
    @get:Input abstract val permissions: ListProperty<String>
    @get:Input abstract val component: Property<String>
    /** Permissions the variant must declare whatever the flavor. */
    @get:Input abstract val required: ListProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun verify() {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)
        val ns = "http://schemas.android.com/apk/res/android"
        fun names(tag: String) = doc.getElementsByTagName(tag).let { list -> (0 until list.length).map { (list.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") } }
        val declared = names("uses-permission") + names("uses-permission-sdk-23")
        val components = listOf("receiver", "service", "activity", "provider").flatMap(::names).filter { Regex(component.get()).matches(it) }
        val problems = if (forbid.get()) permissions.get().filter { it in declared }.map { "declares $it" } + components.map { "declares the updater component $it" }
            else permissions.get().take(2).filter { it !in declared }.map { "lacks $it" } + (if (components.isEmpty()) listOf("lacks the updater receiver") else emptyList())
        val missing = required.get().filter { it !in declared }.map { "lacks $it" }
        val lines = listOf("manifest: ${manifest.get().asFile.name}", "permissions: ${declared.sorted().joinToString()}",
            "receivers: ${names("receiver").sorted().joinToString()}", "updater components: ${components.joinToString().ifEmpty { "none" }}")
        val all = problems + missing
        report.get().asFile.apply { parentFile.mkdirs(); writeText((lines + all.map { "PROBLEM: $it" }).joinToString("\n", postfix = "\n")) }
        if (all.isNotEmpty()) throw GradleException("$name: the merged manifest ${all.joinToString("; ")}. See docs/rfcs/0006-distribution-flavors.md.")
    }
}

androidComponents {
    onVariants { variant ->
        val play = variant.flavorName == "play"
        // Play: the bundle asks for native libraries stored uncompressed and 16 KB-aligned in the APKs Play generates from it
        // (bundle config uncompressNativeLibraries, PAGE_ALIGNMENT_16K; the .aab zip itself may deflate them). Play
        // compresses the download itself. GitHub keeps them compressed in automagik.apk, about half the download.
        if (play) variant.packaging.jniLibs.useLegacyPackaging.set(false)
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyUpdaterManifest>("verify${cap}UpdaterManifest") {
            description = if (play) "Fails if the ${variant.name} manifest has an installer permission or updater receiver."
                else "Fails if the ${variant.name} manifest lost the self-updater's permissions or receiver."
            group = "verification"
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            forbid.set(play); permissions.set(installerPermissions); component.set(updaterComponent); required.set(requiredPermissions)
            report.set(layout.buildDirectory.file("reports/updater-manifest/${variant.name}.txt"))
        }
        // No APK, bundle or check of a variant without its manifest verified.
        tasks.matching { it.name in setOf("assemble$cap", "bundle$cap", "check", "lint$cap") }.configureEach { dependsOn(verify) }
    }
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
    // JNA, which iroh's bindings load, pinned above the 5.15.0 that iroh-android 1.2.0 asks for: 5.15.0's
    // libjnidispatch.so isn't 16 KB page-size ready (x86_64 LOAD segments 4 KB aligned; arm64 read-only RELRO
    // ends inside a writable page), and Play requires it for apps targeting API 35+. 5.17.0's AAR is 16 KB aligned.
    // The AAR is the Android build (classes plus jni/<abi>/libjnidispatch.so); iroh-android already leaves out the
    // desktop jar, so this is the only JNA in the APK. Remove the pin once iroh-android depends on JNA 5.17 or newer.
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    // A bundled QR scanner (camera + ZXing): works without Google Play services.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Draws the desktop's own program icons (SVG), read from the person's desktop at runtime.
    implementation("com.caverock:androidsvg-aar:1.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
