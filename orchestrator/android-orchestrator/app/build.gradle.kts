import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec
import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

fun buildConfigString(value: String): String {
  val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
  return "\"$escaped\""
}

val fallbackBuildTime = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
val fallbackReleaseId = "local-${fallbackBuildTime.replace(Regex("[-:]"), "").removeSuffix("Z")}Z"
val orchestratorReleaseId = providers.environmentVariable("ORCHESTRATOR_RELEASE_ID")
  .orElse(fallbackReleaseId)
  .get()
val orchestratorSourceCommit = providers.environmentVariable("ORCHESTRATOR_SOURCE_COMMIT")
  .orElse("uncommitted")
  .get()
val orchestratorSourceDirty = providers.environmentVariable("ORCHESTRATOR_SOURCE_DIRTY")
  .orElse("true")
  .get()
  .toBooleanStrictOrNull()
  ?: error("ORCHESTRATOR_SOURCE_DIRTY must be true or false")
val orchestratorBuildTime = providers.environmentVariable("ORCHESTRATOR_BUILD_TIME")
  .orElse(fallbackBuildTime)
  .get()

android {
  namespace = "lv.jolkins.pixelorchestrator"
  compileSdk = 35

  defaultConfig {
    applicationId = "lv.jolkins.pixelorchestrator"
    minSdk = 29
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "ORCHESTRATOR_RELEASE_ID", buildConfigString(orchestratorReleaseId))
    buildConfigField("String", "ORCHESTRATOR_SOURCE_COMMIT", buildConfigString(orchestratorSourceCommit))
    buildConfigField("boolean", "ORCHESTRATOR_SOURCE_DIRTY", orchestratorSourceDirty.toString())
    buildConfigField("String", "ORCHESTRATOR_BUILD_TIME", buildConfigString(orchestratorBuildTime))
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }
}

val ticketRootKeyboardAssetDir = layout.buildDirectory.dir("generated/ticketRootKeyboardAssets")
val ticketRootKeyboardAsset = ticketRootKeyboardAssetDir.map { it.file("ticket-root-keyboard") }
val buildTicketRootKeyboard by tasks.registering(Exec::class) {
  val sourceFile = layout.projectDirectory.file("src/main/cpp/ticket_root_keyboard.c")
  val buildScript = layout.projectDirectory.file("../../scripts/android/build_ticket_root_keyboard.sh")
  inputs.file(sourceFile)
  inputs.file(buildScript)
  outputs.file(ticketRootKeyboardAsset)
  doFirst {
    ticketRootKeyboardAsset.get().asFile.parentFile.mkdirs()
  }
  commandLine(buildScript.asFile.absolutePath, ticketRootKeyboardAsset.get().asFile.absolutePath)
}

android.sourceSets.getByName("main").assets.srcDir(ticketRootKeyboardAssetDir)
tasks.matching { task ->
  task.name.startsWith("merge") && task.name.endsWith("Assets") || task.name.contains("Lint", ignoreCase = true)
}.configureEach {
  dependsOn(buildTicketRootKeyboard)
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(project(":core-config"))
  implementation(project(":root-exec"))
  implementation(project(":runtime-installer"))
  implementation(project(":supervisor"))
  implementation(project(":health"))

  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
  implementation("com.google.android.material:material:1.12.0")

  val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
  implementation(composeBom)
  androidTestImplementation(composeBom)
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-test-manifest")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
}
