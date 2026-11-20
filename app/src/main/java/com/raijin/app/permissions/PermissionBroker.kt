package com.raijin.app.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

// ── Contact grouping ──────────────────────────────────────────────────────

enum class ContactGroup { FAMILY, WORK, FRIEND, SELF, UNKNOWN }

data class ResolvedContact(
    val displayName: String,
    val phoneNumber: String,
    val group: ContactGroup,
)

// ── Broker ────────────────────────────────────────────────────────────────

/**
 * Executes Android-side actions proposed by the brain.
 *
 * The brain proposes; the broker checks permissions, infers context,
 * and executes — or returns a clear reason why it cannot.
 */
class PermissionBroker(private val context: Context) {

    companion object {
        private const val TAG = "PermissionBroker"
        private const val RECOVERY_DIR = ".raijin_recovery"

        // ── Keyword sets for contact group inference ──────────────────────
        private val FAMILY_KEYWORDS = setOf(
            "maa", "mama", "papa", "baba", "bhaiya", "didi",
            "bhabhi", "chacha", "chachi", "nana", "nani",
            "dada", "dadi", "mausi", "tau", "tayi",
            "mother", "father", "brother", "sister",
        )
        private val WORK_KEYWORDS = setOf(
            "court", "adj", "djs", "naib", "peon", "clerk",
            "tahsildar", "patwari", "reader", "sho", "si",
            "inspector", "constable", "judge", "advocate", "vakeel",
            "office", "sahab", "sir",
        )
        private val SELF_PREFIXES = setOf("me ", "my ", "mera ", "meri ")

        private val COMMON_APP_PACKAGES = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "instagram" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "netflix" to "com.netflix.mediaclient",
            "linkedin" to "com.linkedin.android",
            "snapchat" to "com.snapchat.android",
            "discord" to "com.discord",
            "reddit" to "com.reddit.frontpage",
            "facebook" to "com.facebook.katana",
            "playstore" to "com.android.vending",
            "play store" to "com.android.vending",
            "camera" to "com.google.android.GoogleCamera",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "files" to "com.google.android.apps.nbu.files",
            "photos" to "com.google.android.apps.photos",
            "messages" to "com.google.android.apps.messaging",
        )
    }

    // ── Permission checks ─────────────────────────────────────────────────

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED

    fun canReadContacts()  = hasPermission(Manifest.permission.READ_CONTACTS)
    fun canCallPhone()     = hasPermission(Manifest.permission.CALL_PHONE)
    fun canSendSms()       = hasPermission(Manifest.permission.SEND_SMS)
    fun canRecordAudio()   = hasPermission(Manifest.permission.RECORD_AUDIO)
    fun canReadStorage()   = if (android.os.Build.VERSION.SDK_INT >= 33)
        hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
    else
        hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Returns true if CALL_PHONE is currently granted. */
    fun ensureCallPermission(): Boolean = canCallPhone()

    /** Returns true if SEND_SMS is currently granted. */
    fun ensureSmsPermission(): Boolean = canSendSms()

    /** Returns true if READ_MEDIA_IMAGES (or READ_EXTERNAL_STORAGE on older APIs) is granted. */
    fun ensureStoragePermission(): Boolean = canReadStorage()

    // ── Contact resolution ────────────────────────────────────────────────

    /**
     * Resolves a phone number directly from a query string.
     * If query already looks like a phone number, returns normalized digits.
     * Otherwise queries the device contacts table.
     */
    fun findContactPhoneNumber(query: String): String? {
        val trimmed = query.trim()
        val digitsOnly = trimmed.replace("[^0-9+]".toRegex(), "")
        if (digitsOnly.length >= 7 && (trimmed.startsWith("+") || trimmed.firstOrNull()?.isDigit() == true)) {
            return digitsOnly
        }

        val contacts = queryContacts(trimmed)
        return contacts.firstOrNull()?.phoneNumber
    }

    /**
     * Returns all contacts whose display name contains [query], ranked by
     * likely group relevance. The brain picks the right one from this list.
     */
    fun queryContacts(query: String): List<ResolvedContact> {
        if (!canReadContacts()) {
            Log.w(TAG, "READ_CONTACTS not granted")
            return emptyList()
        }

        val results = mutableListOf<ResolvedContact>()
        val uri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$query%")

        val cursor: Cursor? = context.contentResolver.query(uri, projection, selection, args, null)
        cursor?.use {
            val nameIdx   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name   = it.getString(nameIdx)  ?: continue
                val number = it.getString(numberIdx) ?: continue
                results += ResolvedContact(
                    displayName = name,
                    phoneNumber = number.replace("\\s".toRegex(), ""),
                    group       = inferGroup(name),
                )
            }
        }

        return results.sortedBy { it.group.ordinal }
    }

    /**
     * Every phone contact on the device, unfiltered. Used by the phonetic
     * contact matcher (CT-4/CT-6): a LIKE pre-filter would drop phonetic
     * matches ("sarma" for "Sharma"), so ranking needs the full list and
     * [io.raijin.brain.ContactScorer] does the narrowing.
     */
    fun queryAllContacts(): List<ResolvedContact> {
        if (!canReadContacts()) {
            Log.w(TAG, "READ_CONTACTS not granted")
            return emptyList()
        }

        val results = mutableListOf<ResolvedContact>()
        val uri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            val nameIdx   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name   = it.getString(nameIdx)  ?: continue
                val number = it.getString(numberIdx) ?: continue
                results += ResolvedContact(
                    displayName = name,
                    phoneNumber = number.replace("\\s".toRegex(), ""),
                    group       = inferGroup(name),
                )
            }
        }
        return results.sortedBy { it.group.ordinal }
    }

    /**
     * Resolves an app name or alias to an installed package name.
     */
    fun resolveAppPackage(nameOrRef: String, alternatives: List<String> = emptyList()): String? {
        val trimmed = nameOrRef.lowercase().trim()
        val normalized = trimmed.replace("-", " ").replace("_", " ")
        val compact = normalized.replace(" ", "")

        // Special case handling for common apps and their business/cloned editions
        if (compact == "whatsapp") {
            if (isPackageInstalled("com.whatsapp")) return "com.whatsapp"
            if (isPackageInstalled("com.whatsapp.w4b")) return "com.whatsapp.w4b"
        }
        if (compact == "youtube") {
            if (isPackageInstalled("com.google.android.youtube")) return "com.google.android.youtube"
        }
        if (compact == "spotify") {
            if (isPackageInstalled("com.spotify.music")) return "com.spotify.music"
        }
        if (compact == "chrome") {
            if (isPackageInstalled("com.android.chrome")) return "com.android.chrome"
        }
        if (compact == "gmail") {
            if (isPackageInstalled("com.google.android.gm")) return "com.google.android.gm"
        }
        if (compact == "maps" || compact == "googlemaps") {
            if (isPackageInstalled("com.google.android.apps.maps")) return "com.google.android.apps.maps"
        }

        COMMON_APP_PACKAGES[normalized]?.let { pkg ->
            if (isPackageInstalled(pkg)) return pkg
        }
        COMMON_APP_PACKAGES[compact]?.let { pkg ->
            if (isPackageInstalled(pkg)) return pkg
        }

        // Direct package name check
        if (nameOrRef.contains(".") && isPackageInstalled(nameOrRef)) {
            return nameOrRef
        }

        // AP-2: fuzzy + phonetic ranking over launcher apps - the same
        // pipeline as contact matching (CT-4), so "wattsapp" or "whats app"
        // still finds WhatsApp. Substring matching could not do that.
        try {
            // AP-1: cached inventory, refreshed on package add/remove/replace.
            val apps = com.raijin.app.apps.AppInventory.get(context)
            // AP-2: learned aliases from explicit owner corrections outrank
            // built-ins. Best-effort: a store hiccup falls back to built-ins.
            val learned = try {
                com.raijin.app.RaijinDatabase.getInstance(context).appAliasDao().getAll()
                    .associate { it.spokenAlias to it.intendedApp }
            } catch (e: Exception) {
                Log.w(TAG, "learned app aliases unavailable: ${e.message}")
                emptyMap()
            }
            (listOf(nameOrRef) + alternatives).distinct()
                .flatMap { io.raijin.brain.AppResolver.rank(it, apps, learned) }
                .maxByOrNull { it.score }
                ?.takeIf { it.score >= io.raijin.brain.AppResolver.RESOLVE_THRESHOLD }
                ?.let { return it.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying launcher apps: ${e.message}")
        }
        return null
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Infers contact group from display name keywords.
     * The brain uses this to rank candidates; it does not make the final call.
     */
    fun inferGroup(displayName: String): ContactGroup {
        val lower = displayName.lowercase()
        if (SELF_PREFIXES.any { lower.startsWith(it) }) return ContactGroup.SELF
        if (FAMILY_KEYWORDS.any { lower.contains(it)  }) return ContactGroup.FAMILY
        if (WORK_KEYWORDS.any   { lower.contains(it)  }) return ContactGroup.WORK
        return ContactGroup.FRIEND
    }

    // ── File operations ────────────────────────────────────────────────────

    /**
     * Moves [file] to a recovery folder instead of deleting immediately.
     * Returns the recovery path, or null on failure.
     * Brain must always call this — never hard-delete.
     */
    fun softDelete(file: File): File? {
        // Deepest execution boundary: file deletion is disabled this release
        // (PRD ST-6 24-hour trash lands with the storage workstream). No
        // caller can move user files through the broker until then.
        Log.w(TAG, "softDelete disabled in this release; refusing ${file.name}")
        return null
    }

    /**
     * Lists files in [directory] matching [mimeHint] (extension filter, nullable = all).
     * Returns empty list if no storage permission.
     */
    fun listFiles(directory: File, mimeHint: String? = null): List<File> {
        if (!canReadStorage()) return emptyList()
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        return directory.walkTopDown()
            .filter { it.isFile }
            .filter { mimeHint == null || it.extension.equals(mimeHint, ignoreCase = true) }
            .toList()
    }

    /**
     * Lists files in recovery folder for a given parent directory.
     * Used to show the user what can still be restored.
     */
    fun listRecovery(parentDir: File): List<File> {
        val recoveryDir = File(parentDir, RECOVERY_DIR)
        if (!recoveryDir.exists()) return emptyList()
        return recoveryDir.listFiles()?.toList() ?: emptyList()
    }

    // ── Exact alarm permission (API 31+) ──────────────────────────────────

    /**
     * Checks whether the app can schedule exact alarms.
     * On API 31+: if permission not granted, launches the system settings page.
     * On API < 31: always returns true (permission not required).
     *
     * @param activity Activity context needed to start the settings intent.
     * @return true if exact alarms can be scheduled right now.
     */
    fun ensureExactAlarmPermission(activity: android.app.Activity): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true

        val am = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (am.canScheduleExactAlarms()) return true

        // Permission not granted — send user to the settings page
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
            ).apply {
                data = android.net.Uri.fromParts("package", activity.packageName, null)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open exact alarm settings: ${e.message}")
        }

        return false
    }
}
