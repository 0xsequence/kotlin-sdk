plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
}

ktlint {
    version.set(libs.versions.ktlint.get())
}

dependencies {
    implementation(libs.kotlin.compiler.embeddable)
    testImplementation(kotlin("test-junit"))
}

val generatorMainClass = "technology.polygon.omswallet.docs.ApiDocsGeneratorKt"
val sdkSourceRoot = rootProject.layout.projectDirectory.dir("oms-wallet-kotlin-sdk/src/main")
val presentationConfig = rootProject.layout.projectDirectory.file("docs/api-groups.conf")
val apiDocument = rootProject.layout.projectDirectory.file("docs/api.md")

fun JavaExec.configureApiDocs(mode: String) {
    group = if (mode == "generate") "documentation" else "verification"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(generatorMainClass)
    args(
        mode,
        sdkSourceRoot.asFile.absolutePath,
        presentationConfig.asFile.absolutePath,
        apiDocument.asFile.absolutePath,
    )
    inputs.dir(sdkSourceRoot)
    inputs.file(presentationConfig)
    if (mode == "generate") {
        outputs.file(apiDocument)
    } else if (mode == "check") {
        inputs.file(apiDocument)
    }
}

tasks.register<JavaExec>("listApiDocsSymbols") {
    description = "Lists source-visible public symbols in source order."
    configureApiDocs("list")
}

val generateApiDocs =
    tasks.register<JavaExec>("generateApiDocs") {
        description = "Generates docs/api.md from Kotlin PSI."
        configureApiDocs("generate")
        dependsOn(tasks.named("test"))
    }

tasks.register<JavaExec>("checkApiDocs") {
    description = "Checks docs/api.md drift and public-symbol grouping."
    configureApiDocs("check")
    dependsOn(
        tasks.named("test"),
        ":oms-wallet-kotlin-sdk:checkPublicApiDoesNotExposeGeneratedWaas",
    )
    mustRunAfter(generateApiDocs)
}
