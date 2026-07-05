package dev.njr.zync.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dev.njr.zync.pairing.AndroidKeystorePasswordProtector
import dev.njr.zync.pairing.PasswordProtector

class BackupSettings(
    context: Context,
    private val protector: PasswordProtector = AndroidKeystorePasswordProtector(
        keyAlias = "zync-backup-passphrase-key",
    ),
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("zync_backup", Context.MODE_PRIVATE)

    fun state(): BackupState =
        BackupState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hasPassphrase = prefs.contains(KEY_PROTECTED_PASSPHRASE),
            lastSuccessAt = prefs.longOrNull(KEY_LAST_SUCCESS_AT),
            lastFailureAt = prefs.longOrNull(KEY_LAST_FAILURE_AT),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            lastBackupName = prefs.getString(KEY_LAST_BACKUP_NAME, null),
            restorePending = prefs.getBoolean(KEY_RESTORE_PENDING, false),
        )

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun savePassphrase(passphrase: CharArray) {
        val protected = protector.protect(passphrase)
        prefs.edit()
            .putString(KEY_PROTECTED_PASSPHRASE, Base64.encodeToString(protected, Base64.NO_WRAP))
            .apply()
    }

    fun passphrase(): CharArray? {
        val encoded = prefs.getString(KEY_PROTECTED_PASSPHRASE, null) ?: return null
        val protected = Base64.decode(encoded, Base64.NO_WRAP)
        return protector.unprotect(protected)
    }

    fun recordSuccess(at: Long, backupName: String) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS_AT, at)
            .putString(KEY_LAST_BACKUP_NAME, backupName)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordFailure(at: Long, error: String) {
        prefs.edit()
            .putLong(KEY_LAST_FAILURE_AT, at)
            .putString(KEY_LAST_ERROR, error.take(240))
            .apply()
    }

    fun requestRestore() {
        prefs.edit().putBoolean(KEY_RESTORE_PENDING, true).apply()
    }

    fun clearRestoreRequest() {
        prefs.edit().putBoolean(KEY_RESTORE_PENDING, false).apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROTECTED_PASSPHRASE = "protected_passphrase"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private const val KEY_LAST_FAILURE_AT = "last_failure_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_BACKUP_NAME = "last_backup_name"
        private const val KEY_RESTORE_PENDING = "restore_pending"
    }
}

data class BackupState(
    val enabled: Boolean,
    val hasPassphrase: Boolean,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastError: String?,
    val lastBackupName: String?,
    val restorePending: Boolean,
)

private fun SharedPreferences.longOrNull(key: String): Long? =
    if (contains(key)) getLong(key, 0L) else null
