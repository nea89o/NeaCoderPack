import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import cuchaz.enigma.command.ConvertMappingsCommand
import moe.nea.zwirn.Zwirn
import net.fabricmc.stitch.commands.tinyv2.TinyV2Reader
import net.fabricmc.stitch.commands.tinyv2.TinyV2Writer
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import net.minecraftforge.srgutils.IMappingFile
import net.minecraftforge.srgutils.INamedMappingFile
import java.net.URL
import java.nio.file.FileSystems
import java.util.regex.Pattern
import java.util.zip.ZipFile


buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://maven.architectury.dev")
        maven("https://maven.fabricmc.net")
        maven("https://maven.minecraftforge.net")
    }
    dependencies {
        classpath("moe.nea:zwirn:1.0-SNAPSHOT")
        classpath("cuchaz:enigma-cli:2.4.1")
    }
}

plugins {
    java
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
val combinedMappingsDir = file("build/mappings")
val overlayMappingsDir = file("mappings")

val mcpSrg = configurations.detachedConfiguration(dependencies.create("de.oceanlabs.mcp:mcp:$minecraftVersion:srg@zip"))
val enigmaSwing = configurations.detachedConfiguration(dependencies.create("cuchaz:enigma-swing:2.4.1:all"))
val mcpStable = configurations.detachedConfiguration(dependencies.create("de.oceanlabs.mcp:mcp_stable:$mcpVersion@zip"))

abstract class DownloadMinecraft : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun downloadMinecraft() {
        val gson = Gson()
        val meta =
            URL("https://launchermeta.mojang.com/mc/game/version_manifest.json").openStream().bufferedReader().use {
                gson.fromJson(it, JsonObject::class.java)
            }
        val version = (meta["versions"] as JsonArray).map { it as JsonObject }.find {
            it["id"].asString == version.get()
        }
        val versionUrl = version!!["url"].asString
        val versionManifest = URL(versionUrl).openStream().bufferedReader().use {
            gson.fromJson(it, JsonObject::class.java)
        }
        val downloadUrl =
            versionManifest.getAsJsonObject("downloads").getAsJsonObject("client").getAsJsonPrimitive("url").asString
        destination.get().asFile.outputStream().use { os ->
            URL(downloadUrl).openStream().use { ins -> ins.copyTo(os) }
        }
    }
}

val downloadMinecraft by tasks.register("downloadMinecraft", DownloadMinecraft::class) {
    destination.set(layout.buildDirectory.file("minecraft-obf.jar"))
    version.set(minecraftVersion)
}


abstract class SeargeToTiny : DefaultTask() {
    @get:InputFiles
    abstract var srgArchive: FileCollection

    @get:OutputFile
    abstract val srgTinyFile: RegularFileProperty

    @TaskAction
    fun load() {
        val zipFile = ZipFile(srgArchive.singleFile)
        val srgFile = INamedMappingFile.load(zipFile.getInputStream(zipFile.getEntry("joined.srg")))
        zipFile.close()
        srgFile.write(srgTinyFile.get().asFile.toPath(), IMappingFile.Format.TINY)
    }
}

abstract class EnrichMcp : DefaultTask() {
    @get:InputFiles
    abstract var mcpArchive: FileCollection

    @get: InputFile
    abstract val srgTinyFile: RegularFileProperty

    @get:OutputFile
    abstract val mcpOutputFile: RegularFileProperty

    @TaskAction
    fun merge() {
        val mcpFs = FileSystems.newFileSystem(mcpArchive.singleFile.toPath())
        val enriched =
            Zwirn.enrichSeargeWithMCP(TinyV2Reader.read(srgTinyFile.get().asFile.toPath()), mcpFs.getPath("/"))
        mcpFs.close()
        TinyV2Writer.write(enriched, mcpOutputFile.asFile.get().toPath())
    }
}

abstract class MapJarTask : DefaultTask() {
    @get:InputFiles
    abstract var inputJar: FileCollection

    @get:InputFile
    abstract val inputTinyFile: RegularFileProperty

    @get:Input
    abstract val inputNamespace: Property<String>

    @get:Input
    abstract val outputNamespace: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun mapJar() {
        val remapper = TinyRemapper.newRemapper().withMappings(
            TinyUtils.createTinyMappingProvider(
                inputTinyFile.get().asFile.toPath(), inputNamespace.get(), outputNamespace.get()
            )
        ).renameInvalidLocals(true).rebuildSourceFilenames(true).invalidLvNamePattern(Pattern.compile("\\$\\$\\d+"))
            .inferNameFromSameLvIndex(true).build()
        OutputConsumerPath.Builder(outputJar.get().asFile.toPath()).build().use { outputConsumer ->
            remapper.readInputsAsync(inputJar.singleFile.toPath())
            remapper.apply(outputConsumer)
            remapper.finish()
        }
    }
}

abstract class ReorderNamespaces : DefaultTask() {
    @get:InputFile
    abstract val inputTiny: RegularFileProperty

    @get:OutputFile
    abstract val outputTiny: RegularFileProperty

    @get:Input
    abstract val namespaceOrder: ListProperty<String>


    @TaskAction
    fun reorderNamespaces() {
        val old = TinyV2Reader.read(inputTiny.asFile.get().toPath())
        val new = Zwirn.renameNamespaces(old, namespaceOrder.get().map { Zwirn.RenameCommand(it, it) })
        TinyV2Writer.write(new, outputTiny.get().asFile.toPath())
    }
}

abstract class UnpackMappings : DefaultTask() {
    @get:InputFile
    abstract val inputTiny: RegularFileProperty

    @get:OutputDirectory
    abstract val outputEnigma: DirectoryProperty

    @TaskAction
    fun reorderNamespaces() {
        ConvertMappingsCommand().run(
            "tinyv2", inputTiny.asFile.get().absolutePath, "enigma", outputEnigma.get().asFile.absolutePath
        )
    }
}

abstract class RepackMappings : DefaultTask() {
    @get:InputDirectory
    abstract val inputEnigma: DirectoryProperty

    @get:OutputFile
    abstract val outputTiny: RegularFileProperty

    @get:Input
    abstract val obfuscatedNamespace: Property<String>

    @get:Input
    abstract val namedNamespace: Property<String>

    @TaskAction
    fun reorderNamespaces() {
        ConvertMappingsCommand().run(
            "enigma",
            inputEnigma.asFile.get().absolutePath,
            "tinyv2:${obfuscatedNamespace.get()}:${namedNamespace.get()}",
            outputTiny.get().asFile.absolutePath
        )
    }
}

abstract class FixFieldDescriptors : DefaultTask() {
    @get:InputFile
    abstract val jarInFirstNameSpace: RegularFileProperty

    @get:InputFile
    abstract val tinyFileIn: RegularFileProperty

    @get:OutputFile
    abstract val tinyFileOut: RegularFileProperty

    @TaskAction
    fun fixFields() {
        val tinyIn = TinyV2Reader.read(tinyFileIn.get().asFile.toPath())
        val fs = FileSystems.newFileSystem(jarInFirstNameSpace.get().asFile.toPath())
        val fixed = Zwirn.fixFieldDescriptorsFromJar(tinyIn, fs.getPath("/"))
        fs.close()
        TinyV2Writer.write(fixed, tinyFileOut.get().asFile.toPath())
    }
}

val seargeTiny by tasks.register("seargeTiny", SeargeToTiny::class) {
    srgArchive = (mcpSrg)
    srgTinyFile.set(layout.buildDirectory.file("mcpSrg.tiny"))
}

val fixFieldDescriptors by tasks.register("fixFieldDescriptor", FixFieldDescriptors::class) {
    tinyFileIn.set(seargeTiny.srgTinyFile)
    tinyFileOut.set(layout.buildDirectory.file("mcpSrgWithFields.tiny"))
    jarInFirstNameSpace.set(downloadMinecraft.destination)
}

val enrichMcp by tasks.register("enrichMcp", EnrichMcp::class) {
    mcpArchive = mcpStable
    srgTinyFile.set(fixFieldDescriptors.tinyFileOut)
    mcpOutputFile.set(layout.buildDirectory.file("mcpEnriched.tiny"))
}

val generateOverlayTiny by tasks.register("overlayTiny", RepackMappings::class) {
    inputEnigma.set(overlayMappingsDir)
    outputTiny.set(layout.buildDirectory.file("overlay.tiny"))
    obfuscatedNamespace.set("searge")
    namedNamespace.set("mcp")
}

abstract class MergeTinies : DefaultTask() {
    @get:InputFile
    abstract val baseTiny: RegularFileProperty

    @get:InputFile
    abstract val overlayTiny: RegularFileProperty

    @get:Input
    abstract val sharedNamespace: Property<String>

    @get:OutputFile
    abstract val outputTiny: RegularFileProperty

    @TaskAction
    fun mergeTinies() {
        val merged = Zwirn.mergeTinyFile(
            TinyV2Reader.read(baseTiny.get().asFile.toPath()),
            TinyV2Reader.read(overlayTiny.get().asFile.toPath()),
            sharedNamespace.get()
        )
        TinyV2Writer.write(merged, outputTiny.get().asFile.toPath())
    }
}

val mergeMcpAndOverlay by tasks.register("mergeMcpAndOverlay", MergeTinies::class) {
    this.baseTiny.set(enrichMcp.mcpOutputFile)
    this.overlayTiny.set(generateOverlayTiny.outputTiny)
    this.sharedNamespace.set("searge")
    this.outputTiny.set(layout.buildDirectory.file("mcpCummedOn.tiny"))
}

val tinyFromMergedEnigma by tasks.register("tinyFromMergedEnigma", RepackMappings::class) {
    this.namedNamespace.set("mcp")
    this.obfuscatedNamespace.set("searge")
    this.inputEnigma.set(combinedMappingsDir)
    this.outputTiny.set(layout.buildDirectory.file("fromWork.tiny"))
}

abstract class DiffTinyFile : DefaultTask() {
    @get:InputFile
    abstract val mergedTiny: RegularFileProperty

    @get:InputFile
    abstract val baseTiny: RegularFileProperty

    @get:OutputFile
    abstract val outputTiny: RegularFileProperty

    @TaskAction
    fun diffTiny() {
        val merged = TinyV2Reader.read(mergedTiny.get().asFile.toPath())
        val base = TinyV2Reader.read(baseTiny.get().asFile.toPath())
        // TODO: unhardcode those namespaces
        val remerged = Zwirn.mergeTinyFile(base, merged, "searge")
        val overlay = Zwirn.createOverlayTinyFile(base, remerged, listOf("searge", "mcp"), "searge")
        TinyV2Writer.write(overlay, outputTiny.get().asFile.toPath())
    }

}

val generateDiffTiny by tasks.register("generateDiffTiny", DiffTinyFile::class) {
    this.mergedTiny.set(tinyFromMergedEnigma.outputTiny)
    this.baseTiny.set(mcpExclusiveBase.outputTiny)
    this.outputTiny.set(layout.buildDirectory.file("overlayGenerated.tiny"))
}

val generateMappingPatches by tasks.register("generateMappingPatches", UnpackMappings::class) {
    this.inputTiny.set(generateDiffTiny.outputTiny)
    this.outputEnigma.set(overlayMappingsDir)
    doFirst {
        overlayMappingsDir.mkdirs()
    }
}

val mcpExclusiveMerged by tasks.register("mcpExclusiveMerged", ReorderNamespaces::class) {
    inputTiny.set(mergeMcpAndOverlay.outputTiny)
    outputTiny.set(layout.buildDirectory.file("mcpExclusiveMerged.tiny"))
    namespaceOrder.set(listOf("searge", "mcp"))
}

val mcpExclusiveBase by tasks.register("mcpExclusiveBase", ReorderNamespaces::class) {
    inputTiny.set(enrichMcp.mcpOutputFile)
    outputTiny.set(layout.buildDirectory.file("mcpExclusiveBase.tiny"))
    namespaceOrder.set(listOf("searge", "mcp"))
}

val unpackMappings by tasks.register("unpackMappings", UnpackMappings::class) {
    this.inputTiny.set(mcpExclusiveMerged.outputTiny)
    this.outputEnigma.set(combinedMappingsDir)
    doFirst {
        combinedMappingsDir.mkdirs()
    }
}

val mapMinecraft by tasks.register("mapMinecraft", MapJarTask::class) {
    this.inputJar = project.files(downloadMinecraft)
    this.inputNamespace.set("notch")
    this.outputNamespace.set("searge")
    this.inputTinyFile.set(enrichMcp.mcpOutputFile)
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


