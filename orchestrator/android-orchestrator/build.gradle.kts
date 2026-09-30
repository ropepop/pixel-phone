plugins {
  id("com.android.application") version "8.5.2" apply false
  id("com.android.library") version "8.5.2" apply false
  id("org.jetbrains.kotlin.android") version "2.1.10" apply false
  id("org.jetbrains.kotlin.jvm") version "2.1.10" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
}

// JVM adapters share the Rust library packaged into Android. Host tests
// exercise that real JNI boundary, including file persistence.
val pixelHealthHostTarget = layout.buildDirectory.dir("pixelHealthHostRust")
val hostLibraryName = when {
  System.getProperty("os.name").startsWith("Mac") -> "libpixel_health.dylib"
  System.getProperty("os.name").startsWith("Windows") -> "pixel_health.dll"
  else -> "libpixel_health.so"
}
val buildPixelHealthHost by tasks.registering(Exec::class) {
  val rustProject = layout.projectDirectory.dir("pixel-health")
  inputs.files(fileTree(rustProject) { include("src/**/*.rs", "Cargo.toml", "Cargo.lock") })
  outputs.file(pixelHealthHostTarget.map { it.file("release/$hostLibraryName") })
  commandLine(
    "cargo", "build", "--locked", "--release", "--lib",
    "--manifest-path", rustProject.file("Cargo.toml").asFile.absolutePath,
    "--target-dir", pixelHealthHostTarget.get().asFile.absolutePath
  )
}
subprojects {
  if (name in setOf("core-config", "health", "supervisor", "runtime-installer", "app")) {
    tasks.withType<Test>().configureEach {
      dependsOn(rootProject.tasks.named("buildPixelHealthHost"))
      systemProperty("java.library.path", pixelHealthHostTarget.get().dir("release").asFile.absolutePath)
    }
  }
}
