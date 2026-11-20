package com.raijin.app.ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accessibility service that automates WhatsApp message delivery for Raijin.
 *
 * ## Why accessibility?
 * WhatsApp has no public API for sending messages. The Intent-only path pre-fills the
 * compose box and opens the chat, but the user must still tap Send. RaijinAccessibilityService
 * watches for the WhatsApp send button and taps it automatically after Raijin opens the chat.
 *
 * ## Flow
 * 1. ActionExecutor calls [sendWhatsAppMessage] with phone + message.
 * 2. Service stores a [PendingSend] and launches a WhatsApp Intent.
 * 3. WhatsApp opens to the contact's chat with the message pre-filled.
 * 4. [onAccessibilityEvent] detects the send button via content description.
 * 5. Service clicks the button, fires callback(true), and clears pending state.
 * 6. A background timeout watcher fires callback(false) if send never happens in 10 s.
 */
class RaijinAccessibilityService : AccessibilityService() {

    // -------------------------------------------------------------------------
    // Companion — static instance & helpers
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "RaijinA11y"

        // WhatsApp send button content descriptions (EN + HI).
        private val SEND_BUTTON_DESCRIPTIONS = setOf("Send", "भेजें")

        // How long to wait for the send button before giving up.
        private const val SEND_TIMEOUT_MS = 10_000L

        // Supported WhatsApp package names.
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        /**
         * Live reference to the running service instance. Null when the service is not bound
         * (user has not granted accessibility permission, or service was killed).
         */
        @Volatile
        var instance: RaijinAccessibilityService? = null
            private set

        /**
         * Returns true if the Raijin accessibility service is currently enabled and connected.
         */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false

            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )
            val pkg = context.packageName
            return enabledServices.any { info ->
                info.resolveInfo?.serviceInfo?.packageName == pkg &&
                    info.resolveInfo?.serviceInfo?.name?.contains("RaijinAccessibilityService") == true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pending-send state
    // -------------------------------------------------------------------------

    /**
     * Represents a WhatsApp message waiting to be sent.
     *
     * @param phoneNumber  E.164 or local number (e.g. "9876543210" or "+919876543210").
     * @param message      Pre-composed message text.
     * @param callback     Receives true on success, false on timeout / failure.
     * @param startedAt    Epoch millis when the send was initiated — used for timeout check.
     */
    private data class PendingSend(
        val phoneNumber: String,
        val message: String,
        val callback: (Boolean) -> Unit,
        val startedAt: Long = System.currentTimeMillis(),
    )

    @Volatile
    private var pendingSend: PendingSend? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        // Fail any pending send before cancelling scope so waiting coroutines
        // are resumed rather than hanging until their own scope is cancelled.
        pendingSend?.callback?.invoke(false)
        pendingSend = null
        serviceScope.cancel()
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Initiates a WhatsApp send.
     *
     * Opens WhatsApp to the target number with [message] pre-filled in the compose box,
     * then watches for the send button and clicks it automatically.
     *
     * @param phoneNumber  Phone number (digits only or with country code, e.g. "919876543210").
     * @param message      Message to send. Will be URI-encoded before passing to WhatsApp.
     * @param callback     Invoked on the main thread with true (sent) or false (timed out).
     */
    fun sendWhatsAppMessage(
        phoneNumber: String,
        message: String,
        callback: (Boolean) -> Unit,
    ) {
        // Cancel any prior pending send — only one in-flight at a time.
        pendingSend?.callback?.invoke(false)

        pendingSend = PendingSend(
            phoneNumber = phoneNumber,
            message     = message,
            callback    = callback,
        )

        // Launch WhatsApp chat with pre-filled text.
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phoneNumber&text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        applicationContext.startActivity(intent)

        // Start timeout watcher.
        startTimeoutWatcher()
    }

    // -------------------------------------------------------------------------
    // Accessibility event handling
    // -------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Only react to WhatsApp events.
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return

        val pending = pendingSend ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                trySendButton(pending)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Searches the current window's node tree for the WhatsApp send button and performs a click.
     */
    private fun trySendButton(pending: PendingSend) {
        val root = rootInActiveWindow ?: return

        val sendNode = findSendButton(root)
        if (sendNode != null && sendNode.isClickable) {
            val clicked = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Send button found — click result: $clicked")

            if (clicked) {
                completePendingSend(success = true)
            }

            sendNode.recycle()
        }

        root.recycle()
    }

    /**
     * Recursively searches [node] and its children for a node whose content description
     * matches one of the known send-button labels.
     */
    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        if (desc in SEND_BUTTON_DESCRIPTIONS) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child)
            if (found != null) return found
            child.recycle()
        }

        return null
    }

    /**
     * Fires the pending callback and clears state.
     */
    private fun completePendingSend(success: Boolean) {
        val pending = pendingSend ?: return
        pendingSend = null
        serviceScope.launch { pending.callback(success) }
        Log.i(TAG, "Pending send completed — success=$success")
    }

    /**
     * Watches for send timeout: if the pending send is older than [SEND_TIMEOUT_MS]
     * and still not completed, fires callback(false).
     */
    private fun startTimeoutWatcher() {
        serviceScope.launch {
            delay(SEND_TIMEOUT_MS)
            val pending = pendingSend ?: return@launch

            val elapsed = System.currentTimeMillis() - pending.startedAt
            if (elapsed >= SEND_TIMEOUT_MS) {
                Log.w(TAG, "Send timed out after ${elapsed}ms")
                completePendingSend(success = false)
            }
        }
    }
}
