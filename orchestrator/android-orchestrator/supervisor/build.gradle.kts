plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":core-config"))
  implementation(project(":root-exec"))
  implementation(project(":health"))

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
}

tasks.test {
  useJUnit()
}
