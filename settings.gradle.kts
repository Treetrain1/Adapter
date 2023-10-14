pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "NeoForged"
            url = uri("https://maven.neoforged.net/")
        }
        maven {
            name = "Minecraft"
            url = uri("https://libraries.minecraft.net")
        }
        maven {
            name = "FabricMC"
            url = uri("https://maven.fabricmc.net")
        }
    }
}

rootProject.name = "Adapter"

if (rootProject.projectDir.name != "adapter") {
    error("Please rename your cloned Adapter repository's folder to \"adapter\"")
}

includeBuild("plugin")