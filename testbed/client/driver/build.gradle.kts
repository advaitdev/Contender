plugins {
    id("fabric-loom") version "1.14.10"
}

version = "1.0.0"
group = "com.uhcranked"

base {
    archivesName.set("uhcr-testbed-client-driver")
}

repositories {
    mavenCentral()
    maven("https://maven.maxhenkel.de/repository/public")
}

loom {
    splitEnvironmentSourceSets()
    mods {
        create("uhcr_testbed_client") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

dependencies {
    "clientCompileOnly"("de.maxhenkel.voicechat:voicechat-api:2.6.20")
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
}

loom {
    runs {
        named("client") {
            client()
            runDir = providers.gradleProperty("testbedRunDir").orElse("run").get()
        }
    }
}

// Loom normally writes this argument file when runClient executes. The testbed
// launches Java directly, so a clean build must prepare the classpath itself.
tasks.matching { it.name == "configureClientLaunch" }.configureEach {
    dependsOn("clientClasses")
    val clientClasspath = providers.provider {
        tasks.named<JavaExec>("runClient").get().classpath
    }
    val launchArguments = layout.buildDirectory.file("loom-cache/argFiles/runClient")
    inputs.files(clientClasspath)
    outputs.file(launchArguments)
    doLast {
        val argumentFile = launchArguments.get().asFile
        argumentFile.parentFile.mkdirs()
        val classpath = clientClasspath.get().asPath
            .replace("\\", "\\\\").replace("\"", "\\\"")
        argumentFile.writeText("-classpath\n\"$classpath\"\n")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

java {
    withSourcesJar()
}
