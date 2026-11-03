pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroup("com.google.testing.platform") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroup("com.google.testing.platform") } }
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.marain87") }
        }
    }
}
rootProject.name = "Leaf"
include(":app")
