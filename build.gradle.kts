import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import xyz.jpenilla.runpaper.task.RunServer
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import io.papermc.paperweight.userdev.PaperweightUser
import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.run.paper) apply false
    alias(libs.plugins.paper.userdev) apply false

    // Kotlin plugin prefers to be applied to parent when it's used in multiple sub-modules.
    kotlin("jvm") version "2.4.0" apply false

    alias(libs.plugins.spotless)
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

val javaVersion: Int = 25

allprojects {
    group = "info.mester.interfaces"
    version = providers.gradleProperty("interfaces_version").get()

    tasks.withType<JavaCompile> {
        sourceCompatibility = javaVersion.toString()
        targetCompatibility = javaVersion.toString()
    }
}

subprojects {
    apply(plugin = "kotlin")
    apply(plugin = "java-library")
    apply<SpotlessPlugin>()

    // Apply paperweight outside the API module
    if (name != "api") {
        apply<PaperweightUser>()

        dependencies {
            extensions.findByType<PaperweightUserDependenciesExtension>()?.paperDevBundle(
                providers.gradleProperty("paper_version").get(),
            )
        }
    }
    if (name != "examples") {
        apply(plugin = "com.vanniktech.maven.publish")
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    configure<SpotlessExtension> {
        kotlin {
            ktlint("1.5.0")
            suppressLintsFor {
                step = "ktlint"
                shortCode = "standard:package-name"
            }
            suppressLintsFor {
                step = "ktlint"
                shortCode = "standard:annotation"
            }
            suppressLintsFor {
                step = "ktlint"
                shortCode = "standard:property-naming"
            }
        }
    }

    tasks.withType<RunServer> {
        minecraftVersion("26.3")
        jvmArgs("-Dio.papermc.paper.suppress.sout.nags=true")
    }

    tasks.withType<KotlinCompile> {
        explicitApiMode.set(ExplicitApiMode.Strict)

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
        }
    }

    if (name != "examples") {
        val requestedTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
        val localOnly = requestedTasks.any { it in setOf("publishToMavenLocal", "publishInterfacesToMavenLocal") } &&
            requestedTasks.none { it.contains("MavenCentral") || it == "publishInterfacesPackages" }
        tasks.withType<Sign>().configureEach {
            enabled = !localOnly
        }

        configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()
            coordinates(group.toString(), if (project.name == "api") "interfaces-api" else project.name, version.toString())
            pom {
                name.set("MESTER interfaces-kotlin ${project.name}")
                description.set("Kotlin Minecraft inventory interfaces with managed item transfers, forked from Noxcrew interfaces-kotlin.")
                inceptionYear.set("2024")
                url.set("https://github.com/MesterMan03/interfaces-kotlin")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("MesterMan03")
                        name.set("Mester")
                        url.set("https://github.com/MesterMan03")
                    }
                    developer {
                        id.set("noxcrew")
                        name.set("Noxcrew")
                        email.set("contact@noxcrew.com")
                    }
                }
                scm {
                    url.set("https://github.com/MesterMan03/interfaces-kotlin")
                    connection.set("scm:git:https://github.com/MesterMan03/interfaces-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/MesterMan03/interfaces-kotlin.git")
                }
            }
        }
    }
}

tasks.register("publishInterfacesToMavenLocal") {
    group = "publishing"
    description = "Publishes both fork modules to Maven Local without signing credentials."
    dependsOn(":api:publishToMavenLocal", ":interfaces:publishToMavenLocal")
}

tasks.register("publishInterfacesPackages") {
    group = "publishing"
    description = "Publishes both fork modules to Maven Central using the MESTER publishing credentials."
    dependsOn(":api:publishToMavenCentral", ":interfaces:publishToMavenCentral")
}
