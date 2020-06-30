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

  private fun InputStream.forCommits(body: (email: String, changed: LineChanges, file: String) -> Unit) {
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
            body(email.substringBefore("@"), LineChanges(
                additions = added.toLongOrNull() ?: 0,
                deletions = removed.toLongOrNull() ?: 0
            ), file)
          } catch (t: Throwable) {
            continue
          }
        }
      }
    }
  }

  private fun contributionsByFolders(folders: List<String>) {
    val inFolderCommits = mutableMapOf<String, LineChanges>()
    val allCommits = mutableMapOf<String, LineChanges>()

    val args = mutableListOf("git", "log", "--numstat")
    if (since != null) {
      args.add("--since=\"$since\"")
    }
    args.add("--no-merges")
    args.add("--pretty=%n%ae")

    Runtime.getRuntime().exec(args.toTypedArray()).inputStream.forCommits { email, changed, file ->
      if (folders.any { file.startsWith(it) }) {
        inFolderCommits[email] = inFolderCommits.getOrDefault(email, LineChanges()) + changed
      }
      allCommits[email] = allCommits.getOrDefault(email, LineChanges()) + changed
    }

    val totalInFolderCommits = inFolderCommits.values.map { it.total() }.sum()

    val contributions = allCommits
        .mapNotNull { (author, commits) ->
          if (commits.total() < 10) return@mapNotNull null

          val inFolder = inFolderCommits.getOrDefault(author, LineChanges())
          val percentageInFolders = inFolder.total()
              .toBigDecimal()
              .multiply(BigDecimal.valueOf(100))
              .divide(commits.total().toBigDecimal(), 2, RoundingMode.HALF_UP)

          val percentageOfFolder = inFolder.total()
              .toBigDecimal()
              .multiply(BigDecimal.valueOf(100))
              .divide(totalInFolderCommits.toBigDecimal(), 2, RoundingMode.HALF_UP)

          return@mapNotNull Contribution(author, percentageInFolders, percentageOfFolder, commits)
        }

    contributions.sortedByDescending { it.percentageInFolder }
        .forEach { (author, percentage, _, lineChanges) ->
          if (percentage < BigDecimal.ONE) return@forEach
          echo(
              "$author writes ${percentage.toPlainString()}% of their code in $folder $lineChanges")
        }

    echo("---")

    contributions.sortedByDescending { it.percentageOfFolder }
        .forEach { (author, _, percentage, lineChanges) ->
          if (percentage < BigDecimal.ONE) return@forEach
          echo("$author accounts for ${percentage.toPlainString()}% of $folder $lineChanges")
        }
  }

  private fun contributionsByAuthor(author: String) {
    val folderCommits = mutableMapOf<String, LineChanges>()
    val allCommits = mutableMapOf<String, LineChanges>()

    val folders = folder ?: listOf("") //default to root

    val args = mutableListOf("git", "log", "--numstat")
    if (since != null) {
      args.add("--since=\"$since\"")
    }
    args.add("--no-merges")
    args.add("--pretty=%n%ae")

    echo("Finding folders with 1% or greater contributions for $author")

    Runtime.getRuntime().exec(args.toTypedArray()).inputStream.forCommits { email, changed, file ->
      val folder = folders.firstOrNull { file.startsWith(it) } ?: return@forCommits
      val nextLevelDirectory = file.substringAfter(folder).trim('/', '{', '}').substringBefore('/')

      if (email == author) {
        folderCommits[nextLevelDirectory] =
          folderCommits.getOrDefault(nextLevelDirectory, LineChanges()) + changed
      }

      allCommits[nextLevelDirectory] =
        allCommits.getOrDefault(nextLevelDirectory, LineChanges()) + changed
    }

    val totalChanges = folderCommits.values.map { it.total() }.sum()

    folderCommits
        .mapNotNull { (folder, changes) ->
          val percentageInFolder = changes.total()
              .toBigDecimal()
              .multiply(BigDecimal.valueOf(100))
              .divide(totalChanges.toBigDecimal(), 2, RoundingMode.HALF_UP)

          return@mapNotNull if (percentageInFolder >= BigDecimal.valueOf(1)) {
            val totalChangesInFolder = allCommits.getOrDefault(folder, LineChanges()).total()

            val percentageOfFolder = changes.total()
                .toBigDecimal()
                .multiply(BigDecimal.valueOf(100))
                .divide(totalChangesInFolder.toBigDecimal(), 2, RoundingMode.HALF_UP)

            AuthorContribution(folder, percentageInFolder, percentageOfFolder, changes)
          } else {
            null
          }
        }
        .sortedByDescending { it.percentageInFolder }
        .forEach { contribution ->
          echo(
              "$author writes ${contribution.percentageInFolder.toPlainString()}% of their code in ${contribution.folder} ${contribution.lineChanges}, accounting for ${contribution.percentageOfFolder.toPlainString()}% of ${contribution.folder}")
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
    val percentageOfFolder: BigDecimal,
    val lineChanges: LineChanges
  )

  private data class AuthorContribution(
    val folder: String,
    val percentageInFolder: BigDecimal,
    val percentageOfFolder: BigDecimal,
    val lineChanges: LineChanges
  )

  private data class LineChanges(
    val additions: Long = 0L,
    val deletions: Long = 0L
  ) {
    fun total() = additions + deletions

    operator fun plus(other: LineChanges) = LineChanges(
        additions = additions + other.additions,
        deletions = deletions + other.deletions
    )

    override fun toString(): String {
      val percentage = deletions.toBigDecimal()
          .multiply(BigDecimal.valueOf(100))
          .divide((additions + deletions).toBigDecimal(), 2, RoundingMode.HALF_UP)
      return "(${percentage.toPlainString()}% deletions)"
    }
  }

  companion object {
    private val spaces = Regex("\\s+")
  }
}

