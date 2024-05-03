import moe.nea.zwirn.plugin.*

plugins {
    java
    id("moe.nea.zwirn") version "1.0-SNAPSHOT"
}

group = "moe.nea"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net") {
        this.metadataSources {
            artifact()
        }
    }
    maven("https://maven.fabricmc.net")
}

val minecraftVersion = "1.8.9"
val mcpVersion = "22-1.8.9"
val combinedMappingsDir = file("work_mappings")
val overlayMappingsDir = file("mappings")

val mcpSrg = configurations.detachedConfiguration(dependencies.create("de.oceanlabs.mcp:mcp:$minecraftVersion:srg@zip"))
val enigmaSwing = configurations.detachedConfiguration(dependencies.create("cuchaz:enigma-swing:2.4.1:all"))
val mcpStable = configurations.detachedConfiguration(dependencies.create("de.oceanlabs.mcp:mcp_stable:$mcpVersion@zip"))

val downloadMinecraft by tasks.register("downloadMinecraft", DownloadMinecraftTask::class) {
    this.minecraftJar.set(layout.buildDirectory.file("minecraft-obf.jar"))
    this.version.set(minecraftVersion)
}


val seargeTiny by tasks.register("seargeTiny", ConvertSeargeToTinyTask::class) {
    this.srgArchive = mcpSrg
    this.srgTinyFile.set(layout.buildDirectory.file("mcpSrg.tiny"))
}

val fixFieldDescriptors by tasks.register("fixFieldDescriptor", FixFieldDescriptorsTask::class) {
    this.inputTinyFile.set(seargeTiny.srgTinyFile)
    this.outputTinyFile.set(layout.buildDirectory.file("mcpSrgWithFields.tiny"))
    this.jarInFirstNamespace.set(downloadMinecraft.minecraftJar)
}

val injectSRGConstructors by tasks.register("injectSrgConstructor", EnrichSeargeWithConstructorsTask::class) {
    this.srgArchive = mcpSrg
    this.srgTinyFile.set(fixFieldDescriptors.outputTinyFile)
    this.enrichedTinyFile.set(layout.buildDirectory.file("mcpEnrichedConstructor.tiny"))
}

val enrichMcp by tasks.register("enrichMcp", EnrichSeargeWithMCPTask::class) {
    this.mcpArchive = mcpStable
    this.srgTinyFile.set(injectSRGConstructors.enrichedTinyFile)
    this.enrichedTinyFile.set(layout.buildDirectory.file("mcpEnriched.tiny"))
}

val generateOverlayTiny by tasks.register("overlayTiny", PackMappingsTask::class) {
    this.inputEnigmaDirectory.set(overlayMappingsDir)
    this.outputTinyFile.set(layout.buildDirectory.file("overlay.tiny"))
    this.obfuscatedNamespace.set("searge")
    this.readableNamespace.set("mcp")
}

val mergeMcpAndOverlay by tasks.register("mergeMcpAndOverlay", MergeTinyFilesTask::class) {
    this.baseTinyFile.set(enrichMcp.enrichedTinyFile)
    this.overlayTinyFile.set(generateOverlayTiny.outputTinyFile)
    this.sharedNamespace.set("searge")
    this.outputTinyFile.set(layout.buildDirectory.file("mcpCummedOn.tiny"))
}

val tinyFromMergedEnigma by tasks.register("tinyFromMergedEnigma", PackMappingsTask::class) {
    this.readableNamespace.set("mcp")
    this.obfuscatedNamespace.set("searge")
    this.inputEnigmaDirectory.set(combinedMappingsDir)
    this.outputTinyFile.set(layout.buildDirectory.file("fromWork.tiny"))
}

val mcpExclusiveBase by tasks.register("mcpExclusiveBase", ReorderNamespacesTask::class) {
    this.inputTinyFile.set(enrichMcp.enrichedTinyFile)
    this.outputTinyFile.set(layout.buildDirectory.file("mcpExclusiveBase.tiny"))
    this.appendNamespaceKeepName("searge")
    this.appendNamespaceKeepName("mcp")
}

val generateDiffTiny by tasks.register("generateDiffTiny", DiffTinyFilesTask::class) {
    this.mergedTinyFile.set(tinyFromMergedEnigma.outputTinyFile)
    this.baseTinyFile.set(mcpExclusiveBase.outputTinyFile)
    this.outputTinyFile.set(layout.buildDirectory.file("overlayGenerated.tiny"))
    this.retainedNamespaces.add("searge")
    this.retainedNamespaces.add("mcp")
    this.sharedNamespace.set("searge")
}

val generateMappingPatches by tasks.register("generateMappingPatches", UnpackMappingsTask::class) {
    this.inputTinyFile.set(generateDiffTiny.outputTinyFile)
    this.outputEnigmaDirectory.set(overlayMappingsDir)
}

val mcpExclusiveMerged by tasks.register("mcpExclusiveMerged", ReorderNamespacesTask::class) {
    this.inputTinyFile.set(mergeMcpAndOverlay.outputTinyFile)
    this.outputTinyFile.set(layout.buildDirectory.file("mcpExclusiveMerged.tiny"))
    this.appendNamespaceKeepName("searge")
    this.appendNamespaceKeepName("mcp")
}

val unpackMappings by tasks.register("unpackMappings", UnpackMappingsTask::class) {
    this.inputTinyFile.set(mcpExclusiveMerged.outputTinyFile)
    this.outputEnigmaDirectory.set(combinedMappingsDir)
}

val mapMinecraft by tasks.register("mapMinecraft", MapJarTask::class) {
    this.inputJar = project.files(downloadMinecraft)
    this.inputNamespace.set("notch")
    this.outputNamespace.set("searge")
    this.mappingTinyFile.set(enrichMcp.enrichedTinyFile)
    this.outputJar.set(project.layout.buildDirectory.file("minecraft-searge.jar"))
}

val launchEnigma by tasks.register("launchEnigma", JavaExec::class) {
    this.classpath(enigmaSwing)
    this.mainClass.set("cuchaz.enigma.gui.Main")
    dependsOn(mapMinecraft)
    this.args(
        "--jar",
        mapMinecraft.outputJar.get().asFile.absolutePath,
        "--mappings",
        combinedMappingsDir.absolutePath,
        "--no-edit-classes",
        "--single-class-tree"
    )
}
