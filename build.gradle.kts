  
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  base
  kotlin("jvm") version "1.3.72"
  id("org.jlleitschuh.gradle.ktlint") version "9.1.1"
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))
  implementation("com.github.ajalt", "clikt", "2.3.0")
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
}

val fatJar = task("fatJar", type = Jar::class) {
  from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
  with(tasks.jar.get() as CopySpec)

  archiveClassifier.set("fat")

  manifest {
    attributes["Main-Class"] = "com.alecstrong.conway.Main"
  }
}

val binaryJar = task("binaryJar") {
  val binaryDir = File(buildDir, "bin")
  val binaryFile = File(binaryDir, "conway")

  doLast {
    val fatJarFile = fatJar.archiveFile.get().asFile

    binaryFile.parentFile.mkdirs()
    binaryFile.writeText("#!/bin/sh\n\nexec java -jar \$0 \"\$@\"\n\n")
    binaryFile.appendBytes(fatJarFile.readBytes())

    binaryFile.setExecutable(true)
  }
}

tasks {
  "assemble" {
    dependsOn(binaryJar)
  }

  binaryJar.dependsOn(fatJar)
}
