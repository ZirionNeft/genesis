import groovy.json.JsonSlurper
import java.util.jar.JarFile

plugins {
    id("java")
}

group = "dev.ifelse.zirion"
val modVersion = providers.gradleProperty("modVersion").get()
version = modVersion

val hytaleVersion = providers.gradleProperty("hytaleVersion").get()
val pluginMainClass = "dev.ifelse.zirion.genesis.GenesisPlugin"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    maven {
        name = "HytaleRelease"
        url = uri("https://maven.hytale.com/release")
        content { includeGroup("com.hypixel.hytale") }
    }
    mavenCentral()
}

val hytaleServerRuntime = configurations.create("hytaleServerRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:$hytaleVersion")
    add(hytaleServerRuntime.name, "com.hypixel.hytale:Server:$hytaleVersion")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.hypixel.hytale:Server:$hytaleVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val metadata = mapOf(
        "modVersion" to modVersion,
        "hytaleVersion" to hytaleVersion,
        "pluginMainClass" to pluginMainClass,
    )
    inputs.properties(metadata)
    filesMatching("manifest.json") { expand(metadata) }
    from(layout.projectDirectory.dir("specs/Genesis_GDD_v0.7")) {
        include("Genesis_GDD_v0.7_Gene_Schema_v2.json", "Genesis_GDD_v0.7_Morphology_Set_v2.json",
            "Genesis_GDD_v0.7_Server_Config.json", "Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json",
            "Genesis_v0.7_Stage_08B_Content_Catalog_v1.json", "Genesis_v0.7_Stage_10_Expression_Rules_v2.json")
        into("genesis/catalogs/v0.7")
    }
}

tasks.jar {
    archiveBaseName.set("Genesis")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val pluginJar = tasks.named<Jar>("jar")

tasks.test {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "com.hypixel.hytale.logger.backend.HytaleLogManager")
    dependsOn(pluginJar)
    inputs.file(pluginJar.flatMap { it.archiveFile })
    systemProperty("genesis.pluginJar", pluginJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("genesis.hytaleVersion", hytaleVersion)
    val gddDirectory = layout.projectDirectory.dir("specs/Genesis_GDD_v0.7")
    inputs.files(fileTree(gddDirectory) { include("*.json") })
    systemProperty("genesis.gddDirectory", gddDirectory.asFile.absolutePath)
}

val verifyPluginJar = tasks.register("verifyPluginJar") {
    group = "verification"
    description = "Checks the Hytale manifest, plugin entry point and prototype NPC resources."
    dependsOn(pluginJar)
    inputs.file(pluginJar.flatMap { it.archiveFile })
    doLast {
        JarFile(pluginJar.get().archiveFile.get().asFile).use { jar ->
            val manifestEntry = checkNotNull(jar.getJarEntry("manifest.json")) {
                "manifest.json must be at the root of the plugin JAR."
            }
            val manifest = jar.getInputStream(manifestEntry).use {
                JsonSlurper().parse(it) as Map<*, *>
            }
            check(manifest["Name"] == "Genesis")
            check(manifest["Version"] == modVersion)
            check(manifest["ServerVersion"] == "=$hytaleVersion")
            check(manifest["Main"] == pluginMainClass)
            check(manifest["IncludesAssetPack"] == true)
            check(jar.getJarEntry(pluginMainClass.replace('.', '/') + ".class") != null) {
                "The manifest Main class is missing from the plugin JAR."
            }
            check(jar.entries().asSequence().none { it.name.startsWith("com/hypixel/hytale/") }) {
                "The plugin JAR must not bundle the Hytale server API."
            }
            check(jar.entries().asSequence().none { it.name.endsWith(".bbmodel") || it.name.startsWith("art/") }) {
                "Blockbench source projects must not be included in the plugin JAR."
            }
            fun readJson(path: String): Map<*, *> {
                val entry = checkNotNull(jar.getJarEntry(path)) { "Missing NPC asset: $path" }
                return jar.getInputStream(entry).use { JsonSlurper().parse(it) as Map<*, *> }
            }
            val role = readJson("Server/NPC/Roles/Genesis/Genesis_TestNPC.json")
            check(role["Appearance"] == "Genesis_TestNPC")
            val model = readJson("Server/Models/Genesis/Genesis_TestNPC.json")
            for (field in listOf("Model", "Texture")) {
                val path = "Common/${model[field]}"
                check(jar.getJarEntry(path) != null) { "Missing model $field resource: $path" }
            }
            val sets = model["AnimationSets"] as Map<*, *>
            val idle = sets["Idle"] as Map<*, *>
            val animations = idle["Animations"] as List<*>
            check(animations.isNotEmpty()) { "The test NPC needs an Idle animation." }
            for (animation in animations) {
                val path = "Common/${(animation as Map<*, *>)["Animation"]}"
                val data = readJson(path)
                check((data["duration"] as Number).toInt() > 0)
                check((data["nodeAnimations"] as Map<*, *>).isNotEmpty())
            }
            for (locale in listOf("en-US", "ru-RU")) {
                val entry = checkNotNull(jar.getJarEntry("Server/Languages/$locale/server.lang"))
                val translations = jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                check(translations.lineSequence().any { it.startsWith("genesis.npcRoles.Genesis_TestNPC.name = ") })
                check(translations.lineSequence().any { it.startsWith("genesis.npcRoles.Genesis_AnatomyProbe.name = ") })
            }
            val probeRole = readJson("Server/NPC/Roles/Genesis/Genesis_AnatomyProbe.json")
            check(probeRole["Appearance"] == "Genesis_AnatomyProbe")
            check(jar.getJarEntry("Common/UI/Custom/Genesis/AnatomyProbe.ui") != null)
            for (suffix in listOf("", "_Orange", "_Whole", "_Whole_Orange")) {
                val probe = readJson("Server/Models/Genesis/Genesis_AnatomyProbe$suffix.json")
                fun checkVisualResource(config: Map<*, *>) {
                    for (field in listOf("Model", "Texture")) {
                        val path = config[field] as String
                        check(path.startsWith("NPC/Genesis/AnatomyProbe/"))
                        check(jar.getJarEntry("Common/$path") != null) { "Missing prototype resource: $path" }
                    }
                }
                checkVisualResource(probe)
                val attachmentSets = probe["RandomAttachmentSets"] as? Map<*, *>
                if (!suffix.startsWith("_Whole")) {
                    checkNotNull(attachmentSets)
                    check(attachmentSets.keys == setOf("head", "tail"))
                    for (set in attachmentSets.values) {
                        val variants = set as Map<*, *>
                        check(variants.keys == setOf("wolf", "fox"))
                        variants.values.forEach { checkVisualResource(it as Map<*, *>) }
                    }
                }
                val animationSets = probe["AnimationSets"] as Map<*, *>
                for (name in listOf("Idle", "Walk")) {
                    val set = animationSets[name] as Map<*, *>
                    val animation = (set["Animations"] as List<*>).single() as Map<*, *>
                    val data = readJson("Common/${animation["Animation"]}")
                    check((data["duration"] as Number).toInt() > 0)
                    check((data["nodeAnimations"] as Map<*, *>).isNotEmpty())
                }
            }
        }
    }
}

tasks.check { dependsOn(verifyPluginJar) }

// Optional development audits: Python is not a build or runtime dependency of Genesis.
// verifyGddCatalogs intentionally fails on inconsistent design sources, including known conflicts.
val pythonExecutable = providers.gradleProperty("pythonExecutable").getOrElse("python3")

tasks.register<Exec>("testGddCatalogValidator") {
    group = "verification"
    description = "Tests the read-only v0.7 design catalog validator (requires Python 3.10+)."
    workingDir(layout.projectDirectory)
    commandLine(pythonExecutable, "-B", "-m", "unittest", "discover",
        "-s", "src/test/python", "-p", "test_gdd_v07_validator.py")
}

tasks.register<Exec>("verifyGddCatalogs") {
    group = "verification"
    description = "Audits the supplied v0.7 design JSON without changing sources (requires Python 3.10+)."
    workingDir(layout.projectDirectory)
    commandLine(pythonExecutable, "-B", "tools/validate_gdd_v07.py", "--json")
}

val stagePlugin = tasks.register<Copy>("stagePlugin") {
    group = "hytale"
    description = "Copies Genesis.jar into the isolated development server's mods directory."
    dependsOn(verifyPluginJar)
    from(pluginJar.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("run/mods"))
    rename { "Genesis.jar" }
}

tasks.register("verifyDevelopment") {
    group = "verification"
    description = "Runs the build, JAR checks and both GDD audits together; live client/server acceptance is separate."
    dependsOn("build", "verifyPluginJar", "testGddCatalogValidator", "verifyGddCatalogs")
}

val hytaleAssetsFile = providers.gradleProperty("hytaleAssets").orNull?.let { file(it) }
val hytaleBindAddress = providers.gradleProperty("hytaleBind").getOrElse("127.0.0.1:5520")

tasks.register<JavaExec>("runServer") {
    group = "hytale"
    description = "Runs Hytale $hytaleVersion locally; requires -PhytaleAssets=/path/to/Assets.zip."
    dependsOn(stagePlugin)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    classpath = hytaleServerRuntime
    mainClass.set("com.hypixel.hytale.Main")
    // JavaExec uses the classpath, so the server JAR's Enable-Native-Access is not applied.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("genesis.anatomyProbe", providers.gradleProperty("genesisAnatomyProbe").getOrElse("false"))
    workingDir(layout.projectDirectory.dir("run"))
    standardInput = System.`in`

    doFirst {
        val assetsFile = hytaleAssetsFile
            ?: throw GradleException("Pass -PhytaleAssets=/absolute/path/to/$hytaleVersion/Assets.zip.")
        if (!assetsFile.isFile) {
            throw GradleException("Hytale Assets.zip does not exist: $assetsFile")
        }
        args("--assets", assetsFile.absolutePath, "--disable-sentry", "--bind", hytaleBindAddress)
    }
}
