package com.raijin.app.selfedit

import android.content.Context
import com.raijin.app.audio.KokoroConfigStore
import com.raijin.app.audio.KokoroVoices
import com.raijin.app.memory.MemoryDao
import com.raijin.app.memory.MemoryFactEntity
import com.raijin.app.prefs.UserPreferencesStore
import io.raijin.brain.AddressForm
import io.raijin.brain.AssistantName
import io.raijin.brain.SelfEdit
import io.raijin.brain.SelfEditEntry
import io.raijin.brain.SelfEditLog

/**
 * Applies voice self-edits to the editable layer only (form of address, speech speed, on-device
 * voice) and keeps an undo log on the phone. Anything else is refused by [SelfEdit.parse].
 * Nothing here touches the network, permissions, code or privacy gates.
 */
class SelfEditExecutor(context: Context, private val memory: MemoryDao) {
    private val appContext = context.applicationContext
    private val prefs = UserPreferencesStore(appContext)
    private val kokoro = KokoroConfigStore(appContext)
    private val logPrefs = appContext.getSharedPreferences("raijin_self_edit", Context.MODE_PRIVATE)
    private val log = SelfEditLog({ logPrefs.getString("log", "") ?: "" }, { logPrefs.edit().putString("log", it).apply() })

    companion object {
        const val K_ADDRESS = "address"
        const val K_SPEED = "speed"
        const val K_VOICE = "voice"
        const val K_NAME = "name"
    }

    /** Applies [cmd]; returns the line to speak. [sourceText] is the owner's words, kept as the remembered fact for an address change. */
    suspend fun apply(cmd: SelfEdit.Command, sourceText: String): String = when (cmd) {
        is SelfEdit.Command.Refuse -> SelfEdit.refusal(cmd.reason)
        SelfEdit.Command.Undo -> undo()
        is SelfEdit.Command.SetAddress -> {
            val before = prefs.addressTerm
            prefs.addressTerm = cmd.term
            val text = sourceText.trim()
            memory.insert(MemoryFactEntity(kind = "FACT", text = text, createdAt = System.currentTimeMillis()))
            log.push(SelfEditEntry(K_ADDRESS, before, cmd.term, text))
            AddressForm.confirmation(cmd.term) + UNDO_HINT
        }
        is SelfEdit.Command.SpeedStep -> setSpeed(SelfEdit.clampSpeed(prefs.ttsSpeed + cmd.delta), if (cmd.delta > 0) "faster" else "slower")
        is SelfEdit.Command.SetSpeed -> setSpeed(SelfEdit.clampSpeed(cmd.factor), "at ${"%.1f".format(cmd.factor)} times")
        is SelfEdit.Command.SetVoiceGender -> {
            val pending = prefs.pendingGenderForName
            prefs.pendingGenderForName = null
            val msg = setVoice(cmd.male)
            if (pending != null) "Got it, $pending is ${if (cmd.male) "male" else "female"}. $msg" else msg
        }
        is SelfEdit.Command.SetName -> {
            val before = prefs.assistantName
            log.push(SelfEditEntry(K_NAME, before, cmd.name))
            prefs.assistantName = cmd.name
            val male = cmd.male ?: AssistantName.genderOf(cmd.name)
            if (male == null) {
                prefs.pendingGenderForName = cmd.name
                "Okay, I'm ${cmd.name} now. Is ${cmd.name} a male or female name? Say male or female and I'll pick the voice.$UNDO_HINT"
            } else {
                prefs.pendingGenderForName = null
                "Okay, I'm ${cmd.name} now. " + setVoice(male)
            }
        }
    }

    private fun setVoice(male: Boolean): String {
        val voice = if (male) KokoroVoices.MALE else KokoroVoices.FEMALE
        val cfg = kokoro.load()
        log.push(SelfEditEntry(K_VOICE, cfg.voice, voice.id))
        kokoro.save(cfg.copy(voice = voice.id))
        val note = if (cfg.enabled) "" else " Turn on the on-device voice in Settings to hear it."
        return "Okay, I'll use the ${if (male) "male" else "female"} voice.$note$UNDO_HINT"
    }

    private fun setSpeed(new: Float, words: String): String {
        val before = prefs.ttsSpeed
        log.push(SelfEditEntry(K_SPEED, before.toString(), new.toString()))
        prefs.ttsSpeed = new
        return "Okay, I'll speak $words.$UNDO_HINT"
    }

    private suspend fun undo(): String {
        val e = log.pop() ?: return "There is nothing to undo."
        when (e.key) {
            K_ADDRESS -> {
                prefs.addressTerm = e.before
                e.memoryText?.let { t -> memory.getAll().filter { it.text == t }.forEach { memory.deleteById(it.id) } }
            }
            K_SPEED -> e.before?.toFloatOrNull()?.let { prefs.ttsSpeed = it }
            K_NAME -> e.before?.let { prefs.assistantName = it }
            K_VOICE -> e.before?.let { kokoro.save(kokoro.load().copy(voice = it)) }
        }
        return "Undone."
    }

    private val UNDO_HINT get() = " Say undo to change it back."
}
