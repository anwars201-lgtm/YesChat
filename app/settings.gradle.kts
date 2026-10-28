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
    }
}

rootProject.name = "AppmuseApp"
// Single-module-at-root: the root project IS the application module (no
// include(":app")). Sources live at src/main/ relative to this directory, which
// becomes /workspace/app in the container — so source is app/src/main/kotlin and
// the APK lands at app/build/outputs/apk/... (see ADR-0027).
