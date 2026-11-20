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
        // مخزن رسمی کتابخونه‌ی Vosk (اگه از Maven Central نیومد، از اینجا میاد)
        maven { url = uri("https://alphacephei.com/maven/") }
    }
}
rootProject.name = "HeyTab"
include(":app")
