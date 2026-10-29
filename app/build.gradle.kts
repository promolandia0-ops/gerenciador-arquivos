import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val ks = Properties().apply { val f = rootProject.file("keystore/keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
  namespace = "br.geracaoai.arquivos"
  compileSdk = 35
  defaultConfig { applicationId = "br.geracaoai.arquivos"; minSdk = 30; targetSdk = 35; versionCode = 4; versionName = "1.3.0" }
  signingConfigs { create("release") { if (ks.isNotEmpty()) { storeFile = rootProject.file(ks["storeFile"] as String); storePassword = ks["storePassword"] as String; keyAlias = ks["keyAlias"] as String; keyPassword = ks["keyPassword"] as String } } }
  buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); signingConfig = signingConfigs.getByName("release") } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true; aidl = false; buildConfig = true }
  lint { checkReleaseBuilds = false; abortOnError = false }
}
dependencies {
  val bom = platform("androidx.compose:compose-bom:2024.09.00")
  implementation(bom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.fragment:fragment-ktx:1.8.3")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.profileinstaller:profileinstaller:1.3.1")
  implementation("androidx.biometric:biometric:1.1.0")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation("io.coil-kt:coil-compose:2.7.0")
  implementation("io.coil-kt:coil-video:2.7.0")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  testImplementation("junit:junit:4.13.2")
}
