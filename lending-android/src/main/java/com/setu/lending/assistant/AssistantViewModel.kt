package com.setu.lending.assistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.setu.lending.domain.Assessment
import com.setu.lending.domain.Completion
import com.setu.lending.domain.FieldType
import com.setu.lending.domain.FormField
import com.setu.lending.domain.FormSchema
import com.setu.lending.domain.Products
import com.setu.lending.domain.Profile
import com.setu.lending.domain.computeCompletion
import com.setu.lending.voice.AudioPlayer
import com.setu.lending.voice.AudioRecorder
import com.setu.lending.voice.SarvamClient
import com.setu.lending.voice.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Phase { IDLE, SPEAKING, LISTENING, THINKING, AWAIT_TEXT, DONE }

data class ChatLine(val fromUser: Boolean, val text: String)

/**
 * Runs the voice-first onboarding conversation: greets, asks the customer's
 * name, then guides them field by field — filling the form as it understands
 * them, recommending products, and driving completion to 100%.
 */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val recorder = AudioRecorder()
    private val player = AudioPlayer(app)
    private val sarvam = SarvamClient { Settings.apiKey(appContext) }

    // ---- observable state for Compose ----
    val values = mutableStateMapOf<String, String>()
    val messages = mutableStateListOf<ChatLine>()

    var phase by mutableStateOf(Phase.IDLE); private set
    var caption by mutableStateOf(""); private set
    var amplitude by mutableStateOf(0f); private set
    var products by mutableStateOf<List<Assessment>>(emptyList()); private set
    var completion by mutableStateOf(computeCompletion(emptyMap())); private set
    var submitted by mutableStateOf(false); private set
    var referenceId by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set

    // voice availability (mic permission granted AND api key present)
    var voiceEnabled by mutableStateOf(false); private set

    private var pendingField: FormField? = null
    private var lastFilled: List<Pair<String, String>> = emptyList() // id -> previous value
    private var awaitingSubmit = false
    private var started = false
    private var turnJob: Job? = null

    init {
        // mirror mic amplitude while listening so the Siri orb reacts to voice
        viewModelScope.launch {
            recorder.amplitude.collect { amp ->
                if (phase == Phase.LISTENING) amplitude = amp
            }
        }
    }

    fun setVoiceEnabled(enabled: Boolean) { voiceEnabled = enabled }

    /** Called once the overlay is shown; auto-starts the assistant. */
    fun startOnboarding() {
        if (started) return
        started = true
        val greeting = "Namaste! Welcome to Setu Finance. I'm Saathi, your lending assistant. " +
            "I'll help you apply in just a couple of minutes. To begin, may I know your name?"
        pendingField = FormSchema.byId["fullName"]
        startLoop(greeting)
    }

    /** Restart the whole conversation from scratch. */
    fun restart() {
        turnJob?.cancel()
        recorder.requestStop()
        player.stop()
        values.clear()
        messages.clear()
        products = emptyList()
        pendingField = null
        lastFilled = emptyList()
        awaitingSubmit = false
        submitted = false
        referenceId = null
        error = null
        refreshCompletion()
        started = false
        phase = Phase.IDLE
        startOnboarding()
    }

    /** User typed instead of (or in addition to) speaking. */
    fun onUserText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val next = handleUtterance(t)
        startLoop(next)
    }

    /** From AWAIT_TEXT, tap the mic to listen again without re-speaking. */
    fun listenAgain() {
        if (voiceEnabled) startLoop("")
    }

    /** While LISTENING, finish capture now and let transcription proceed. */
    fun stopListening() {
        if (phase == Phase.LISTENING) recorder.requestStop()
    }

    /** Stop the current playback/recording and idle. */
    fun stopTurn() {
        turnJob?.cancel()
        recorder.requestStop()
        player.stop()
        if (phase != Phase.DONE) phase = Phase.AWAIT_TEXT
    }

    // ---- the conversation loop ----

    private fun startLoop(firstPrompt: String) {
        turnJob?.cancel()
        recorder.requestStop()
        player.stop()
        turnJob = viewModelScope.launch {
            var prompt = firstPrompt
            while (isActive) {
                if (prompt.isNotBlank()) {
                    addBot(prompt)
                    phase = Phase.SPEAKING
                    caption = "Speaking…"
                    speak(prompt)
                }
                if (!isActive) break

                if (submitted) { phase = Phase.DONE; break }

                if (!(voiceEnabled && Settings.hasApiKey(appContext))) {
                    phase = Phase.AWAIT_TEXT
                    caption = if (!Settings.hasApiKey(appContext))
                        "Add your Sarvam API key to talk, or type your answer below."
                    else "Type your answer below."
                    break
                }

                phase = Phase.LISTENING
                caption = "Listening…"
                val wav = recorder.record()
                if (!isActive) break

                phase = Phase.THINKING
                caption = "Thinking…"
                val heard = sarvam.transcribe(wav, sttLanguage())
                if (!isActive) break

                if (heard.isNullOrBlank()) {
                    error = sarvam.lastError
                    phase = Phase.AWAIT_TEXT
                    caption = "I didn't catch that — tap the mic to retry or type below."
                    break
                }
                error = null
                prompt = handleUtterance(heard)
            }
        }
    }

    private suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!Settings.hasApiKey(appContext)) return
        val wav = sarvam.synthesize(text, Settings.language(appContext), Settings.speaker(appContext))
        if (wav.isNotEmpty()) player.play(wav) else error = sarvam.lastError
    }

    private fun sttLanguage(): String {
        // "unknown" lets Sarvam auto-detect Hindi/English/etc.
        return "unknown"
    }

    // ---- understanding + form filling (mirrors the web copilot) ----

    private val actionIntents = setOf("recommend", "eligibility", "emi", "documents", "help", "greet", "affirm", "deny")

    private fun handleUtterance(text: String): String {
        addUser(text)
        val parsed = Nlu.parse(text)

        // if the application is complete and we're waiting on a submit decision
        if (awaitingSubmit) {
            if (parsed.intents.any { it == "affirm" || it == "apply" } ||
                Regex("\\bsubmit\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                submit()
                return "Your application is submitted. Reference $referenceId. A credit officer will call you shortly. Thank you for choosing Setu Finance!"
            }
            if (parsed.intents.contains("deny")) {
                awaitingSubmit = false
                return "No problem. What would you like to change?"
            }
        }

        var filled = applyEntities(parsed.entities)
        val isAction = parsed.intents.any { it in actionIntents }
        // Consent is answered by "yes"/"I agree", which are otherwise action
        // intents — allow those through so we don't loop asking for consent.
        val consentAffirm = pendingField?.type == FieldType.CHECKBOX &&
            Regex("^(yes|yeah|yep|sure|ok|okay|agree|i agree|accept|confirm|done|haan)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        if (filled.isEmpty() && pendingField != null && (!isAction || consentAffirm)) {
            fillPending(text)?.let { filled = listOf(it) }
        }

        val sb = StringBuilder()
        if (filled.isNotEmpty()) {
            sb.append("Got it — updated ${joinLabels(filled)}. ")
        }

        // product / eligibility / emi / documents intents produce extra content
        var extra = ""
        if (parsed.intents.contains("recommend") || parsed.intents.contains("eligibility")) {
            extra = recommendSpoken()
        } else if (parsed.intents.contains("emi")) {
            extra = emiSpoken()
        } else if (parsed.intents.contains("documents")) {
            extra = docsSpoken()
        }

        when {
            parsed.intents.contains("greet") && filled.isEmpty() && extra.isEmpty() ->
                return "Hi! What do you need funds for? You can tell me the loan type and amount."
            parsed.intents.contains("help") ->
                return "Sure — just describe your situation and I'll fill the form. Tell me your loan type, amount, monthly income and employment, and I'll do the rest."
        }

        refreshCompletion()
        val next = nextBestField()
        when {
            next != null -> {
                pendingField = next
                sb.append(askFor(next))
            }
            else -> {
                awaitingSubmit = true
                sb.append("That's everything I need. Shall I submit your application now? Say yes to confirm.")
            }
        }
        if (extra.isNotEmpty()) return extra + " " + sb.toString()
        return sb.toString()
    }

    private fun applyEntities(e: Entities): List<String> {
        val filled = mutableListOf<String>()
        val prevs = mutableListOf<Pair<String, String>>()
        for ((id, value) in e.toFieldValues()) {
            val field = FormSchema.byId[id] ?: continue
            val prev = values[id] ?: ""
            values[id] = value
            prevs.add(id to prev)
            filled.add(id)
        }
        if (filled.isNotEmpty()) {
            lastFilled = prevs
            pendingField?.let { if (filled.contains(it.id)) pendingField = null }
            refreshCompletion()
        }
        return filled
    }

    private fun fillPending(text: String): String? {
        val f = pendingField ?: return null
        val v = text.trim()
        if (v.length > 60) return null
        val newValue: String = when {
            f.type == FieldType.CHECKBOX || f.id == "consent" -> {
                if (!Regex("^(yes|yeah|yep|sure|ok|okay|agree|i agree|accept|confirm|done|haan)\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(v)) return null
                "true"
            }
            f.type == FieldType.CHOICE -> {
                val lower = v.lowercase()
                val opt = f.choices.firstOrNull {
                    it.value.lowercase() == lower || it.label.lowercase().contains(lower)
                } ?: return null
                opt.value
            }
            f.type == FieldType.NUMBER -> {
                val amt = Nlu.parseAmount(text)
                val n = amt ?: v.replace(Regex("[^\\d.]"), "").toDoubleOrNull()?.toLong()
                (n ?: return null).toString()
            }
            else -> {
                if (!validText(f.id, v)) return null
                v
            }
        }
        val prev = values[f.id] ?: ""
        values[f.id] = newValue
        lastFilled = listOf(f.id to prev)
        pendingField = null
        refreshCompletion()
        return f.id
    }

    private fun validText(id: String, value: String): Boolean {
        val words = value.split(Regex("\\s+")).size
        return when (id) {
            "fullName" -> Regex("^[a-z][a-z .']{1,40}$", RegexOption.IGNORE_CASE).matches(value) && words <= 4
            "city" -> Regex("^[a-z][a-z .'-]{1,30}$", RegexOption.IGNORE_CASE).matches(value) && words <= 3
            "pincode" -> Regex("^[1-9]\\d{5}$").matches(value)
            "purpose" -> words <= 10
            else -> true
        }
    }

    private fun nextBestField(): FormField? {
        val missingIds = completion.missing.associateBy { it.id }
        for (id in FormSchema.ASK_ORDER) missingIds[id]?.let { return it }
        return completion.missing.firstOrNull()
    }

    private fun askFor(f: FormField): String = when (f.id) {
        "loanType" -> "Which loan would you like — personal, home, gold, business, car, two-wheeler or education?"
        "amount" -> "How much would you like to borrow? For example, five lakh."
        "monthlyIncome" -> "What is your monthly income? You can say sixty thousand a month, or twelve L P A."
        "employment" -> "Are you salaried, self-employed, or a business owner?"
        "fullName" -> "What is your full name, as per your PAN card?"
        "mobile" -> "What is your ten digit mobile number?"
        "age" -> "How old are you?"
        "pan" -> "Could you tell me your PAN number?"
        "city" -> "Which city are you in?"
        "pincode" -> "What is your six digit pincode?"
        "email" -> "What email should we use for updates?"
        "aadhaar" -> "And your twelve digit Aadhaar number? It will be kept masked."
        "cibil" -> "Do you know your CIBIL score? It helps me get you a better rate. If not, just say skip."
        "tenure" -> "Over how many months would you like to repay?"
        "consent" -> "Last step — do you agree to let us verify your details and check your credit report? Please say yes to confirm."
        else -> "Could you provide your ${f.label.lowercase()}?"
    }

    private fun joinLabels(ids: List<String>): String {
        val labels = ids.map { FormSchema.labelFor(it) }
        return when (labels.size) {
            1 -> labels[0]
            else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
        }
    }

    // ---- recommendations / emi / docs ----

    private fun profile(): Profile {
        fun num(id: String): Long? = values[id]?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()?.toLong()
        return Profile(
            loanType = values["loanType"],
            amount = num("amount"),
            monthlyIncome = num("monthlyIncome"),
            existingEmi = num("existingEmi") ?: 0,
            employment = values["employment"],
            cibil = num("cibil")?.toInt(),
            age = num("age")?.toInt()
        )
    }

    private fun recommendSpoken(): String {
        val recs = Products.recommend(profile()).take(3)
        products = recs
        val top = recs.firstOrNull() ?: return "Tell me your loan type and income and I'll find the best product."
        val elig = if (top.eligible) "up to ${Products.inrShort(top.maxEligible)}" else "once we have a few more details"
        return "Based on your profile, a ${top.product.name} looks best — $elig at ${"%.2f".format(top.rate)} percent, " +
            "with an EMI around ${Products.inr(top.emi)} a month."
    }

    private fun emiSpoken(): String {
        val p = profile()
        val product = Products.byId(p.loanType) ?: return "Tell me the loan type and amount and I'll estimate your EMI."
        if (p.amount == null) return "How much would you like to borrow? Then I can estimate your EMI."
        val r = Products.assess(product, p)
        return "For a ${product.name} of ${Products.inr(p.amount)} at ${"%.2f".format(r.rate)} percent over " +
            "${r.tenureMonths} months, your EMI is about ${Products.inr(r.emi)} per month."
    }

    private fun docsSpoken(): String {
        val product = Products.byId(values["loanType"]) ?: Products.byId("personal")!!
        return "For a ${product.name} you'll typically need: ${product.docs.joinToString(", ")}."
    }

    // ---- form editing from the UI (manual overrides stay in sync) ----

    fun setField(id: String, value: String) {
        values[id] = value
        refreshCompletion()
    }

    fun toggleConsent(checked: Boolean) {
        values["consent"] = if (checked) "true" else "false"
        refreshCompletion()
    }

    fun submit(): Boolean {
        refreshCompletion()
        if (completion.pct < 100) {
            error = "Please complete: ${completion.missing.joinToString(", ") { it.label }}"
            return false
        }
        submitted = true
        awaitingSubmit = false
        referenceId = "LC-" + (100000..999999).random().toString(36).uppercase()
        phase = Phase.DONE
        caption = "Submitted"
        return true
    }

    private fun refreshCompletion() {
        completion = computeCompletion(values)
    }

    override fun onCleared() {
        super.onCleared()
        turnJob?.cancel()
        recorder.requestStop()
        player.stop()
    }

    private fun addBot(text: String) { messages.add(ChatLine(false, text)) }
    private fun addUser(text: String) { messages.add(ChatLine(true, text)) }
}
