package com.example.data.storage

import com.example.data.local.VaultFolderEntity
import java.util.Locale

enum class RenameCaseMode(val label: String) {
  KEEP("Keep"),
  LOWERCASE("lower"),
  UPPERCASE("UPPER"),
  TITLE_CASE("Title"),
}

data class BatchRenameConfig(
  val sanitizeIllegalChars: Boolean = true,
  val replaceSpacesWithUnderscores: Boolean = false,
  val collapseSeparators: Boolean = true,
  val trimWhitespaceAndDots: Boolean = true,
  val findText: String = "",
  val replaceText: String = "",
  val prefix: String = "",
  val suffix: String = "",
  val caseMode: RenameCaseMode = RenameCaseMode.KEEP,
  val numberingEnabled: Boolean = false,
  val numberingStart: Int = 1,
  val numberingPadding: Int = 3,
  val preserveExtension: Boolean = true,
)

data class RenamePreviewItem(
  val id: String,
  val currentName: String,
  val proposedName: String,
  val isEncrypted: Boolean = false,
  val relativeSubPath: String = "",
  val isChanged: Boolean = currentName != proposedName,
  val conflictNote: String? = null,
)

data class BatchRenameResult(
  val renamedCount: Int,
  val skippedCount: Int,
  val failedCount: Int,
  val firstError: String? = null,
)

object BatchRenameEngine {

  private val illegalCharRegex = Regex("[/\\\\:*?\"<>|\\x00-\\x1F\\x7F]")
  private val multiSpaceRegex = Regex("\\s+")
  private val multiUnderscoreRegex = Regex("_+")
  private val multiDashRegex = Regex("-+")

  fun splitFileName(name: String, preserveExtension: Boolean): Pair<String, String> {
    if (!preserveExtension) return name to ""
    val dotIndex = name.lastIndexOf('.')
    if (dotIndex <= 0 || dotIndex >= name.length - 1) return name to ""
    val ext = name.substring(dotIndex)
    // Treat very long or whitespace-containing suffixes as part of the stem rather than an extension
    if (ext.length > 13 || ext.any { it.isWhitespace() }) {
      return name to ""
    }
    return name.substring(0, dotIndex) to ext
  }

  fun sanitizeBaseName(
    raw: String,
    sanitizeIllegalChars: Boolean = true,
    replaceSpacesWithUnderscores: Boolean = false,
    collapseSeparators: Boolean = true,
    trimWhitespaceAndDots: Boolean = true,
    fallback: String = "item",
  ): String {
    var result = raw
    if (sanitizeIllegalChars) {
      result = result.replace("..", "_")
      result = illegalCharRegex.replace(result, "_")
    }
    if (replaceSpacesWithUnderscores) {
      result = multiSpaceRegex.replace(result, "_")
    }
    if (collapseSeparators) {
      result = multiSpaceRegex.replace(result, " ")
      result = multiUnderscoreRegex.replace(result, "_")
      result = multiDashRegex.replace(result, "-")
    }
    if (trimWhitespaceAndDots) {
      result = result.trim().trim('.', '_', '-').trim()
    }
    if (result.isBlank()) {
      result = fallback
    }
    return result
  }

  fun sanitizeFolderName(rawName: String): String =
    sanitizeBaseName(
      raw = rawName,
      sanitizeIllegalChars = true,
      replaceSpacesWithUnderscores = false,
      collapseSeparators = true,
      trimWhitespaceAndDots = true,
      fallback = "Vault Folder",
    )

  fun applyCaseMode(text: String, mode: RenameCaseMode): String = when (mode) {
    RenameCaseMode.KEEP -> text
    RenameCaseMode.LOWERCASE -> text.lowercase(Locale.US)
    RenameCaseMode.UPPERCASE -> text.uppercase(Locale.US)
    RenameCaseMode.TITLE_CASE -> toTitleCase(text)
  }

  private fun toTitleCase(text: String): String {
    if (text.isEmpty()) return text
    val sb = StringBuilder(text.length)
    var capitalizeNext = true
    for (ch in text) {
      if (ch.isLetterOrDigit()) {
        if (capitalizeNext && ch.isLetter()) {
          sb.append(ch.titlecaseChar())
        } else if (ch.isLetter()) {
          sb.append(ch.lowercaseChar())
        } else {
          sb.append(ch)
        }
        capitalizeNext = false
      } else {
        sb.append(ch)
        capitalizeNext = true
      }
    }
    return sb.toString()
  }

  fun transformName(
    originalName: String,
    config: BatchRenameConfig,
    sequenceIndex: Int = 0,
    isFolder: Boolean = false,
  ): String {
    val preserveExt = !isFolder && config.preserveExtension
    val (rawStem, rawExt) = splitFileName(originalName, preserveExt)

    var stem = rawStem
    if (config.findText.isNotEmpty()) {
      stem = stem.replace(config.findText, config.replaceText)
    }

    stem = sanitizeBaseName(
      raw = stem,
      sanitizeIllegalChars = config.sanitizeIllegalChars,
      replaceSpacesWithUnderscores = config.replaceSpacesWithUnderscores,
      collapseSeparators = config.collapseSeparators,
      trimWhitespaceAndDots = config.trimWhitespaceAndDots,
      fallback = if (isFolder) "Vault Folder" else "file",
    )

    stem = applyCaseMode(stem, config.caseMode)

    val cleanPrefix = if (config.sanitizeIllegalChars) {
      illegalCharRegex.replace(config.prefix, "_").replace("..", "_")
    } else {
      config.prefix
    }
    val cleanSuffix = if (config.sanitizeIllegalChars) {
      illegalCharRegex.replace(config.suffix, "_").replace("..", "_")
    } else {
      config.suffix
    }

    val seqPart = if (config.numberingEnabled) {
      val number = config.numberingStart + sequenceIndex
      val padWidth = config.numberingPadding.coerceIn(1, 6)
      "_" + number.toString().padStart(padWidth, '0')
    } else {
      ""
    }

    var combinedStem = "$cleanPrefix$stem$cleanSuffix$seqPart"
    if (config.replaceSpacesWithUnderscores) {
      combinedStem = multiSpaceRegex.replace(combinedStem, "_")
    }
    if (config.sanitizeIllegalChars) {
      combinedStem = illegalCharRegex.replace(combinedStem, "_").replace("..", "_")
    }
    if (config.trimWhitespaceAndDots) {
      combinedStem = combinedStem.trim().trimEnd('.')
    }
    if (combinedStem.isBlank()) {
      combinedStem = if (isFolder) "Vault Folder" else "file"
    }

    val cleanExt = if (rawExt.isEmpty()) {
      ""
    } else {
      val safeExt = illegalCharRegex.replace(rawExt, "")
      when (config.caseMode) {
        RenameCaseMode.LOWERCASE -> safeExt.lowercase(Locale.US)
        RenameCaseMode.UPPERCASE -> safeExt.uppercase(Locale.US)
        RenameCaseMode.TITLE_CASE -> safeExt.lowercase(Locale.US)
        RenameCaseMode.KEEP -> safeExt
      }
    }

    return "$combinedStem$cleanExt"
  }

  fun computeBatchFileRenamePlan(
    selectedFiles: List<VaultFileItem>,
    allFolderFiles: List<VaultFileItem>,
    config: BatchRenameConfig,
  ): List<RenamePreviewItem> {
    val selectedIds = selectedFiles.map { it.id }.toSet()
    val usedNamesBySubPath = mutableMapOf<String, MutableSet<String>>()
    for (item in allFolderFiles) {
      if (item.id !in selectedIds) {
        usedNamesBySubPath.getOrPut(item.relativeSubPath) { mutableSetOf() }
          .add(item.displayName.lowercase(Locale.US))
      }
    }

    val previews = mutableListOf<RenamePreviewItem>()
    selectedFiles.forEachIndexed { index, file ->
      val candidate = transformName(
        originalName = file.displayName,
        config = config,
        sequenceIndex = index,
        isFolder = false,
      )
      val scopeSet = usedNamesBySubPath.getOrPut(file.relativeSubPath) { mutableSetOf() }
      val (finalName, note) = deduplicateName(candidate, scopeSet, preserveExtension = config.preserveExtension)
      scopeSet.add(finalName.lowercase(Locale.US))
      previews.add(
        RenamePreviewItem(
          id = file.id,
          currentName = file.displayName,
          proposedName = finalName,
          isEncrypted = file.isEncrypted,
          relativeSubPath = file.relativeSubPath,
          isChanged = finalName != file.displayName,
          conflictNote = note,
        ),
      )
    }
    return previews
  }

  fun computeBatchFolderRenamePlan(
    selectedFolders: List<VaultFolderEntity>,
    allFolders: List<VaultFolderEntity>,
    config: BatchRenameConfig,
  ): List<RenamePreviewItem> {
    val selectedIds = selectedFolders.map { it.id }.toSet()
    val usedNames = allFolders
      .filter { it.id !in selectedIds }
      .mapTo(mutableSetOf()) { it.name.lowercase(Locale.US) }

    val previews = mutableListOf<RenamePreviewItem>()
    selectedFolders.forEachIndexed { index, folder ->
      val candidate = transformName(
        originalName = folder.name,
        config = config.copy(preserveExtension = false),
        sequenceIndex = index,
        isFolder = true,
      )
      val (finalName, note) = deduplicateName(candidate, usedNames, preserveExtension = false)
      usedNames.add(finalName.lowercase(Locale.US))
      previews.add(
        RenamePreviewItem(
          id = folder.id,
          currentName = folder.name,
          proposedName = finalName,
          isEncrypted = folder.isLocked,
          isChanged = finalName != folder.name,
          conflictNote = note,
        ),
      )
    }
    return previews
  }

  private fun deduplicateName(
    candidate: String,
    usedLowerNames: Set<String>,
    preserveExtension: Boolean,
  ): Pair<String, String?> {
    if (candidate.lowercase(Locale.US) !in usedLowerNames) {
      return candidate to null
    }
    val (stem, ext) = splitFileName(candidate, preserveExtension)
    var counter = 1
    var next = "${stem}_$counter$ext"
    while (next.lowercase(Locale.US) in usedLowerNames) {
      counter++
      next = "${stem}_$counter$ext"
    }
    return next to "Auto-numbered to avoid name collision"
  }
}
