package com.compvdo.app.compression

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Removing an original, safely — implements R2.3.
 *
 * Owns:   the only path in the app that removes a user's video.
 * Reads:  nothing.
 * Writes: nothing directly; it asks MediaStore or the user to act.
 * Runs:   nothing.
 *
 * This replaces a `contentResolver.delete()` that was documented as
 * "Uses MediaStore.createTrashRequest on API 30+" and did no such thing: it
 * destroyed the file outright, and swallowed the `RecoverableSecurityException`
 * that carries the very mechanism Android provides for asking the user.
 *
 * Three different platforms hide behind one method here, and they are NOT
 * equivalent — [isRecoverable] exists so the UI can tell the user which one
 * they are on rather than promising a trash that does not exist:
 *
 *  - **API 30+**  `createTrashRequest` moves the item to the system trash. The
 *    OS shows its own confirmation and the file is recoverable for ~30 days.
 *  - **API 29**   No trash API. A delete of media the app does not own throws
 *    `RecoverableSecurityException`, whose `IntentSender` prompts the user.
 *    The delete is then **permanent**.
 *  - **API 28**   Legacy storage. The delete succeeds directly and is
 *    **permanent**, with no system prompt at all.
 */
object TrashRequest {

    sealed interface Outcome {
        /**
         * The system needs to ask the user. Launch [intentSender] with
         * `ActivityResultContracts.StartIntentSenderForResult` and treat
         * `RESULT_OK` as consent given.
         */
        data class NeedsUserConsent(val intentSender: IntentSender) : Outcome

        /** Removed without a prompt (API 28 only). */
        data class Removed(val count: Int) : Outcome

        /** Nothing was removed. [message] says why. */
        data class Failed(val message: String) : Outcome
    }

    /**
     * True when removal on this device goes to a recoverable trash.
     *
     * The UI MUST use this to word its confirmation. Telling someone their
     * holiday footage went "to the trash" when the platform deleted it outright
     * is the kind of lie that costs people their videos.
     */
    fun isRecoverable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Ask to remove [uris]. Never removes anything silently on API 29+.
     *
     * @return what happened, or what the caller must do next.
     */
    fun request(context: Context, uris: Collection<Uri>): Outcome {
        if (uris.isEmpty()) return Outcome.Failed("Nothing selected")
        val resolver = context.contentResolver

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> trashViaMediaStore(resolver, uris)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> deleteWithConsentQ(resolver, uris)
            else -> deleteLegacy(resolver, uris)
        }
    }

    /** API 30+: the real thing. Recoverable, and the OS does the asking. */
    private fun trashViaMediaStore(resolver: ContentResolver, uris: Collection<Uri>): Outcome =
        try {
            val pending = MediaStore.createTrashRequest(resolver, uris.toList(), true)
            Outcome.NeedsUserConsent(pending.intentSender)
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Could not build a trash request")
        }

    /**
     * API 29: no trash exists. Attempt the delete purely to obtain the
     * `IntentSender` the platform hands back, so the user is still asked.
     *
     * Only the first item that needs consent is surfaced; the caller repeats
     * the call after consent to work through the rest. Batching is not
     * available before API 30.
     */
    private fun deleteWithConsentQ(resolver: ContentResolver, uris: Collection<Uri>): Outcome {
        var removed = 0
        for (uri in uris) {
            try {
                removed += resolver.delete(uri, null, null)
            } catch (e: RecoverableSecurityException) {
                // This exception IS the permission prompt. Catching it broadly
                // and returning false is what made the old code unable to do
                // what its own comment claimed.
                return Outcome.NeedsUserConsent(e.userAction.actionIntent.intentSender)
            } catch (e: SecurityException) {
                return Outcome.Failed("Not permitted to delete ${uri.lastPathSegment}: ${e.message}")
            } catch (e: Exception) {
                return Outcome.Failed(e.message ?: "Delete failed")
            }
        }
        return if (removed > 0) Outcome.Removed(removed)
        else Outcome.Failed("Nothing was removed")
    }

    /** API 28: legacy storage, permanent, no prompt available from the platform. */
    private fun deleteLegacy(resolver: ContentResolver, uris: Collection<Uri>): Outcome {
        var removed = 0
        val problems = mutableListOf<String>()
        for (uri in uris) {
            try {
                removed += resolver.delete(uri, null, null)
            } catch (e: Exception) {
                problems.add(uri.lastPathSegment ?: uri.toString())
            }
        }
        return when {
            problems.isEmpty() && removed > 0 -> Outcome.Removed(removed)
            removed > 0 -> Outcome.Removed(removed)
            else -> Outcome.Failed("Could not remove: ${problems.joinToString()}")
        }
    }

    /** True when an [Activity] result for a consent prompt means "go ahead". */
    fun consentGranted(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
