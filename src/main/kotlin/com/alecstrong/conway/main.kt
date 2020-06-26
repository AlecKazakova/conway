@file:JvmName("Main")

package com.alecstrong.conway

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode

fun main(vararg args: String) {
  ConwayCli().main(args.toList())
}

internal class ConwayCli: CliktCommand(name = "conway") {
  val folder by option("-D",
      help = "Input directories <directory1>,<directory2>").split(",")
  val since: String? by option("--since")

  private val inFolderCommits = mutableMapOf<String, Int>()
  private val allCommits = mutableMapOf<String, Int>()

  private fun InputStream.forCommits(body: (email: String, changed: Int, file: String) -> Unit) {
    InputStreamReader(this).useLines { sequence ->
      val iterator = sequence.iterator()
      iterator.next() // Skip initial newline

      while (iterator.hasNext()) {
        val email = iterator.next()

        iterator.next() // Skip newline

        while (iterator.hasNext()) {
          val linesChanged = iterator.next()
          if (linesChanged.isEmpty()) break

          try {
            val (added, removed, file) = linesChanged.split(spaces)
            body(email.substringBefore("@"),
                ((added.toIntOrNull() ?: continue) + (removed.toIntOrNull() ?: continue)), file)
          } catch (t: Throwable) {
            continue
          }
        }
      }
    }
  }

  override fun run() {
    val since = if (since == null) "" else "--since=\"$since\""

    Runtime.getRuntime().exec(
        arrayOf("git", "log", "--numstat", since, "--no-merges",
            "--pretty=%n%ae")).inputStream.forCommits { email, changed, file ->
      if (folder!!.any { file.startsWith(it) }) {
        inFolderCommits[email] = inFolderCommits.getOrDefault(email, 0) + changed
      }
      allCommits[email] = allCommits.getOrDefault(email, 0) + changed
    }

    val totalInFolderCommits = inFolderCommits.map { it.value }.sum()

    val contributions = allCommits
        .mapNotNull { (author, commits) ->
          if (commits < 10) return@mapNotNull null

          val inFolder = inFolderCommits.getOrDefault(author, 0)
          val percentageInFolders = inFolder
              .toBigDecimal()
              .multiply(BigDecimal.valueOf(100))
              .divide(commits.toBigDecimal(), 2, RoundingMode.HALF_UP)

          val percentageOfFolder = inFolder
              .toBigDecimal()
              .multiply(BigDecimal.valueOf(100))
              .divide(totalInFolderCommits.toBigDecimal(), 2, RoundingMode.HALF_UP)

          return@mapNotNull Contribution(author, percentageInFolders, percentageOfFolder)
        }

    contributions.sortedByDescending { it.percentageInFolder }
        .forEach { (author, percentage, _) ->
          if (percentage < BigDecimal.ONE) return@forEach
          echo("$author writes ${percentage.toPlainString()}% of their code in $folder")
        }

    echo("---")

    contributions.sortedByDescending { it.percentageOfFolder }
        .forEach { (author, _, percentage) ->
          if (percentage < BigDecimal.ONE) return@forEach
          echo("$author accounts for ${percentage.toPlainString()}% of $folder")
        }
  }

  private data class Contribution(
    val author: String,
    val percentageInFolder: BigDecimal,
    val percentageOfFolder: BigDecimal
  )

  companion object {
    private val spaces = Regex("\\s+")
  }
}

