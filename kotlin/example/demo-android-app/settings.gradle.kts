pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
  }
}

rootProject.name = "My Application"
include(":app")

// Exercise this checkout, including the new mdoc API, without a locally published artifact.
includeBuild("../../android") {
  dependencySubstitution {
    substitute(module("io.inji:tuvali")).using(project(":"))
  }
}
