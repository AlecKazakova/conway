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

internal class ConwayCli : CliktCommand(name = "conway") {
  val folder by option("--folders",
      help = "Input directories <directory1>,<directory2>").split(",")
  val author by option("--author", help = "alias to search by")
  val since: String? by option("--since")

  private fun InputStream.forCommits(body: (email: String, changed: Int, file: String) -> Unit) {
    InputStreamReader(this).useLines { sequence ->
      val iterator = sequence.iterator()

      if (!iterator.hasNext()) {
        echo("There were no commits for the given arguments", err = true)
        return
      }
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

  private fun contributionsByFolders(folders: List<String>) {
    val inFolderCommits = mutableMapOf<String, Int>()
    val allCommits = mutableMapOf<String, Int>()

    val args = mutableListOf("git", "log", "--numstat")
    if (since != null) {
      args.add("--since=\"$since\"")
    }
    args.add("--no-merges")
    args.add("--pretty=%n%ae")

    Runtime.getRuntime().exec(args.toTypedArray()).inputStream.forCommits { email, changed, file ->
      if (folders.any { file.startsWith(it) }) {
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

  private fun contributionsByAuthor(author: String) {
    val folderCommits = mutableMapOf<String, Long>()

    val folders = folder ?: listOf("") //default to root

    val args = mutableListOf("git", "log", "--numstat", "--author", author)
    if (since != null) {
      args.add("--since=\"$since\"")
    }
    args.add("--no-merges")
    args.add("--pretty=%n%ae")

    echo("Finding folders with 1% or greater contributions for $author")

    Runtime.getRuntime().exec(args.toTypedArray()).inputStream.forCommits { _, changed, file ->
      val folder = folders.firstOrNull { file.startsWith(it) } ?: return@forCommits
      val nextLevelDirectory = file.substringAfter(folder).trim('/', '{', '}').substringBefore('/')

      folderCommits[nextLevelDirectory] = folderCommits.getOrDefault(nextLevelDirectory, 0) + changed
    }

    val totalChanges = folderCommits.values.sum()

    folderCommits.mapNotNull { (folder, changes) ->
      val percentageInFolder = changes
          .toBigDecimal()
          .multiply(BigDecimal.valueOf(100))
          .divide(totalChanges.toBigDecimal(), 2, RoundingMode.HALF_UP)

      return@mapNotNull if (percentageInFolder >= BigDecimal.valueOf(1)) {
        folder to percentageInFolder
      } else {
        null
      }
    }.sortedByDescending { it.second }
        .forEach { (folder, changes) ->
          echo("$author writes ${changes.toPlainString()}% of their code in $folder")
        }
  }

  override fun run() {
    if (author != null) {
      contributionsByAuthor(author!!)
    } else if (folder != null) {
      contributionsByFolders(folder!!)
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

