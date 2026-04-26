pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "pixel-root-orchestrator"

include(
  ":app",
  ":core-config",
  ":root-exec",
  ":runtime-installer",
  ":supervisor",
  ":health"
)
