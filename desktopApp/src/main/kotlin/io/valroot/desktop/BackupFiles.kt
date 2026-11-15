package io.valroot.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

/** Native file selection for encrypted portable backups, exports and CSV imports. */
class BackupFiles {
    fun save(
        name: String,
        bytes: ByteArray,
    ): Boolean {
        val path = choose(FileDialog.SAVE, "Export file", name) ?: return false
        Files.write(path, bytes)
        return true
    }

    fun chooseBackupDestination(): Path? = choose(FileDialog.SAVE, "Save encrypted backup", "valroot-backup.valroot")

    /** Lists only Valroot backups (`.valroot`). */
    fun chooseBackup(): Path? = choose(FileDialog.LOAD, "Restore encrypted backup", extension = ".valroot")

    fun chooseImport(): Path? = choose(FileDialog.LOAD, "Import CSV file")

    /** Selects exchange exports together so overlapping reports can be reviewed as one batch. */
    fun chooseExchangeExports(): List<Path> {
        val dialog = FileDialog(null as Frame?, "Import exchange exports", FileDialog.LOAD)
        return try {
            dialog.isMultipleMode = true
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("csv", "xlsx", "zip", "xls") }
            dialog.isVisible = true
            dialog.files.map { it.toPath() }
        } finally {
            dialog.dispose()
        }
    }

    private fun choose(
        mode: Int,
        title: String,
        name: String = "valroot-backup.enc",
        extension: String? = null,
    ): Path? {
        val dialog = FileDialog(null as Frame?, title, mode)
        try {
            if (mode == FileDialog.SAVE) dialog.file = name
            extension?.let { wanted -> dialog.setFilenameFilter { _, file -> file.endsWith(wanted, ignoreCase = true) } }
            dialog.isVisible = true
            val name = dialog.file ?: return null
            return Path.of(dialog.directory, name)
        } finally {
            dialog.dispose()
        }
    }
}
