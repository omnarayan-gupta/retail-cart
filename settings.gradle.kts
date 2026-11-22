pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "retail-cart"

include(":apps:app")
include(":libs:product-client")

// Template convention: each module's build file is named after the module, e.g. apps/app/app.gradle.kts
fun useModuleNamedBuildFiles(project: ProjectDescriptor) {
    project.buildFileName = "${project.name}.gradle.kts"
    project.children.forEach { useModuleNamedBuildFiles(it) }
}
rootProject.children.forEach { useModuleNamedBuildFiles(it) }
