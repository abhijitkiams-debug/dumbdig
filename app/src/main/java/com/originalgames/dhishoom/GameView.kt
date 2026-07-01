package com.originalgames.dhishoom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.originalgames.dhishoom.net.BluetoothLink
import com.originalgames.dhishoom.net.Msg
import com.originalgames.dhishoom.net.NetLink
import com.originalgames.dhishoom.net.NetState
import com.originalgames.dhishoom.net.Packet
import com.originalgames.dhishoom.net.Snapshot
import com.originalgames.dhishoom.net.WifiLink
import java.io.Closeable
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * The whole game: a [SurfaceView] driven by its own 60 fps render thread.
 *
 * DHISHOOM is a quick best-of-3 fighting game. A round is 60 seconds, so a whole
 * match lands in 3–4 minutes — decide a winner and pass the phone. You fight a
 * difficulty-scaled bot solo, or a friend over Bluetooth or a Wi-Fi hotspot with
 * no internet. Networking is host-authoritative: the host simulates and streams
 * world snapshots; the client streams its inputs and renders what it's told.
 *
 * Everything drawn here is generated from primitive shapes and text — there are
 * no imported images, fonts, or sounds anywhere in the project.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class Screen { TITLE, MODE, DIFFICULTY, CHAR, NET_ROLE, NET_DEVICES, NET_CONNECT, NET_CHAR, FIGHT }
    private enum class Phase { INTRO, FIGHTING, ROUND_END, MATCH_END }
    private enum class Role { NONE, HOST, CLIENT }
    private enum class Transport { WIFI, BLUETOOTH }

    private val prefs = Prefs(context)
    private val sound = SoundManager(prefs.soundEnabled)
    private val haptics = Haptics(context, prefs.hapticsEnabled)
    private val particles = ParticleSystem()

    private var thread: GameThread? = null
    private var screen = Screen.TITLE

    // --- Geometry (set in surfaceChanged) ---
    private var w = 0f
    private var h = 0f
    private var groundY = 0f
    private var fighterHeight = 0f
    private var leftBound = 0f
    private var rightBound = 0f
    private var startXA = 0f
    private var startXB = 0f

    // --- Fighters & match state ---
    private var fA: Fighter? = null
    private var fB: Fighter? = null
    private var preview: Fighter? = null
    private var ai: Ai? = null

    private var phase = Phase.INTRO
    private var roundNum = 1
    private var roundsWonA = 0
    private var roundsWonB = 0
    private var roundWinner = -1
    private var timerTicks = ROUND_TICKS
    private var introTicks = 0
    private var endTicks = 0
    private var banner = 0
    private var matchRecorded = false

    private val comboCount = intArrayOf(0, 0)
    private var comboPopupTimer = 0
    private var comboShownCount = 0
    private var comboSide = 0
    private var bestComboThisMatch = 0

    private var frameStartedA = 0
    private var frameStartedB = 0

    // --- Selection ---
    private var myFighterIndex = prefs.selectedFighter.coerceIn(0, Roster.all.size - 1)
    private var oppFighterIndex = 2
    private var difficulty = Ai.Difficulty.NORMAL
    private var playerSide = 0   // 0 = controls fighter A (host/solo), 1 = controls B (client)

    // --- Juice ---
    private var bgPhase = 0f
    private var shakeTime = 0
    private var shakeMag = 0f
    private val flash = intArrayOf(0, 0)

    // --- Networking ---
    private var role = Role.NONE
    private var transport = Transport.WIFI
    private var netLink: NetLink? = null
    private var connector: Closeable? = null
    @Volatile private var remoteButtons = 0
    @Volatile private var pendingSnapshot: Snapshot? = null
    @Volatile private var remoteFighterIndex = 2
    @Volatile private var netStartA = -1
    @Volatile private var netStartB = -1
    @Volatile private var netStatus = ""
    @Volatile private var netError: String? = null
    private var netSendAcc = 0
    private var prevAhp = 0f
    private var prevBhp = 0f
    private var btPeers: List<BluetoothLink.BtPeer> = emptyList()

    // --- Input ---
    @Volatile private var localButtons = 0

    // --- Touch regions ---
    private class Ctrl(val cx: Float, val cy: Float, val r: Float, val btn: Int)
    private val controls = ArrayList<Ctrl>()
    private val rectFx = RectF()
    private val rectBack = RectF()
    private val modeRects = ArrayList<RectF>()
    private val diffRects = ArrayList<RectF>()
    private val roleRects = ArrayList<RectF>()
    private val resultRects = ArrayList<RectF>()
    private val rectArrowL = RectF()
    private val rectArrowR = RectF()
    private val rectConfirm = RectF()

    // --- Paints (reused; never allocate in the hot loop) ---
    private val skyPaint = Paint()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD
        )
        textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint().apply { color = 0xCC000000.toInt() }
    private val boxA = RectF()
    private val boxB = RectF()

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // ----------------------------------------------------------------------
    // Surface lifecycle
    // ----------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread = GameThread(holder).also { it.running = true; it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        groundY = h * 0.86f
        fighterHeight = h * 0.44f
        leftBound = w * 0.03f
        rightBound = w * 0.97f
        startXA = w * 0.30f
        startXB = w * 0.70f
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, groundY,
            Color.parseColor("#2A1430"), Color.parseColor("#120A14"),
            Shader.TileMode.CLAMP
        )
        // Keep existing fighters consistent with the new geometry.
        fA?.let { it.height = fighterHeight; it.groundY = groundY }
        fB?.let { it.height = fighterHeight; it.groundY = groundY }
        layoutUi()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread?.let {
            it.running = false
            var retry = true
            while (retry) {
                try { it.join(); retry = false } catch (_: InterruptedException) {}
            }
        }
        thread = null
    }

    fun onActivityPause() {
        // Leaving a networked fight ends it cleanly for both sides.
        if (role != Role.NONE) teardownNet()
    }

    private fun layoutUi() {
        // FX toggle, top-right (on menus).
        val fw = w * 0.12f
        val fh = h * 0.075f
        rectFx.set(w - fw - w * 0.03f, h * 0.04f, w - w * 0.03f, h * 0.04f + fh)
        rectBack.set(w * 0.03f, h * 0.04f, w * 0.03f + w * 0.12f, h * 0.04f + fh)

        buildStack(modeRects, 3, h * 0.30f)
        buildStack(diffRects, 3, h * 0.30f)
        buildStack(roleRects, 2, h * 0.34f)
        buildStack(resultRects, 3, h * 0.44f)

        val ah = h * 0.16f
        rectArrowL.set(w * 0.05f, h * 0.40f, w * 0.05f + w * 0.10f, h * 0.40f + ah)
        rectArrowR.set(w * 0.85f, h * 0.40f, w * 0.85f + w * 0.10f, h * 0.40f + ah)
        rectConfirm.set(w / 2f - w * 0.18f, h * 0.82f, w / 2f + w * 0.18f, h * 0.82f + h * 0.12f)

        // On-screen fight controls.
        controls.clear()
        val mr = h * 0.095f   // movement button radius
        val ar = h * 0.085f   // action button radius
        controls.add(Ctrl(w * 0.085f, h * 0.84f, mr, Btn.LEFT))
        controls.add(Ctrl(w * 0.20f, h * 0.84f, mr, Btn.RIGHT))
        controls.add(Ctrl(w * 0.145f, h * 0.55f, h * 0.085f, Btn.JUMP))
        controls.add(Ctrl(w * 0.80f, h * 0.84f, ar, Btn.PUNCH))
        controls.add(Ctrl(w * 0.93f, h * 0.66f, ar, Btn.KICK))
        controls.add(Ctrl(w * 0.67f, h * 0.66f, ar, Btn.BLOCK))
        controls.add(Ctrl(w * 0.80f, h * 0.50f, h * 0.075f, Btn.SPECIAL))
    }

    private fun buildStack(into: ArrayList<RectF>, n: Int, top: Float) {
        into.clear()
        val bw = w * 0.44f
        val bh = h * 0.135f
        val gap = h * 0.04f
        val cx = w / 2f
        var y = top
        repeat(n) {
            into.add(RectF(cx - bw / 2f, y, cx + bw / 2f, y + bh))
            y += bh + gap
        }
    }

    // ----------------------------------------------------------------------
    // Input
    // ----------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (screen == Screen.FIGHT && phase == Phase.FIGHTING && netError == null) {
            updateFightTouch(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            handleTap(event.x, event.y)
        }
        return true
    }

    /** Rebuilds the held-button mask from every active pointer over a control. */
    private fun updateFightTouch(event: MotionEvent) {
        val upIndex = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            MotionEvent.ACTION_CANCEL -> -2
            else -> -1
        }
        if (upIndex == -2) { localButtons = 0; return }
        var mask = 0
        for (p in 0 until event.pointerCount) {
            if (p == upIndex) continue
            mask = mask or buttonAt(event.getX(p), event.getY(p))
        }
        localButtons = mask
    }

    private fun buttonAt(x: Float, y: Float): Int {
        for (c in controls) {
            if (hypot(x - c.cx, y - c.cy) <= c.r * 1.15f) return c.btn
        }
        return 0
    }

    private fun handleTap(x: Float, y: Float) {
        if (netError != null) { teardownNet(); goTitle(); return }
        when (screen) {
            Screen.TITLE -> {
                if (rectFx.contains(x, y)) toggleFx() else { sound.tap(); screen = Screen.MODE }
            }
            Screen.MODE -> {
                if (rectBack.contains(x, y)) { goTitle(); return }
                when {
                    modeRects[0].contains(x, y) -> { sound.tap(); screen = Screen.DIFFICULTY }
                    modeRects[1].contains(x, y) -> { sound.tap(); transport = Transport.WIFI; screen = Screen.NET_ROLE }
                    modeRects[2].contains(x, y) -> { sound.tap(); transport = Transport.BLUETOOTH; screen = Screen.NET_ROLE }
                }
            }
            Screen.DIFFICULTY -> {
                if (rectBack.contains(x, y)) { sound.tap(); screen = Screen.MODE; return }
                val pick = when {
                    diffRects[0].contains(x, y) -> Ai.Difficulty.EASY
                    diffRects[1].contains(x, y) -> Ai.Difficulty.NORMAL
                    diffRects[2].contains(x, y) -> Ai.Difficulty.HARD
                    else -> null
                }
                if (pick != null) { sound.tap(); difficulty = pick; role = Role.NONE; playerSide = 0; enterCharSelect() }
            }
            Screen.CHAR -> handleCharTap(x, y, net = false)
            Screen.NET_ROLE -> handleRoleTap(x, y)
            Screen.NET_DEVICES -> handleDevicesTap(x, y)
            Screen.NET_CONNECT -> { if (rectBack.contains(x, y)) { teardownNet(); screen = Screen.NET_ROLE } }
            Screen.NET_CHAR -> handleCharTap(x, y, net = true)
            Screen.FIGHT -> handleFightOverlayTap(x, y)
        }
    }

    private fun enterCharSelect() {
        myFighterIndex = clampUnlocked(myFighterIndex, net = false)
        preview = Fighter(Roster.all[myFighterIndex], fighterHeight, groundY).also { it.reset(w / 2f, 1) }
        screen = Screen.CHAR
    }

    private fun handleCharTap(x: Float, y: Float, net: Boolean) {
        when {
            rectBack.contains(x, y) -> { sound.tap(); if (net) teardownNet().also { screen = Screen.NET_ROLE } else screen = Screen.MODE }
            rectArrowL.contains(x, y) -> cycleFighter(-1, net)
            rectArrowR.contains(x, y) -> cycleFighter(+1, net)
            rectConfirm.contains(x, y) -> {
                sound.tap()
                if (!net) {
                    prefs.selectedFighter = myFighterIndex
                    oppFighterIndex = Random.nextInt(Roster.all.size)
                    startMatch(Roster.all[myFighterIndex], Roster.all[oppFighterIndex])
                } else if (role == Role.HOST) {
                    // Host locks both choices and kicks off the match.
                    prefs.selectedFighter = myFighterIndex
                    val bIdx = remoteFighterIndex.coerceIn(0, Roster.all.size - 1)
                    netLink?.send(Packet.start(myFighterIndex, bIdx))
                    startMatch(Roster.all[myFighterIndex], Roster.all[bIdx])
                }
                // Client's confirm does nothing; it waits for START.
            }
        }
    }

    private fun cycleFighter(dir: Int, net: Boolean) {
        var i = myFighterIndex
        do {
            i = Roster.clampIndex(i + dir)
        } while (!net && !Roster.isUnlocked(i, prefs.careerWins) && i != myFighterIndex)
        myFighterIndex = i
        preview = Fighter(Roster.all[i], fighterHeight, groundY).also { it.reset(w / 2f, 1) }
        sound.tap()
        if (net && role == Role.CLIENT) netLink?.send(Packet.hello(i))
    }

    private fun clampUnlocked(index: Int, net: Boolean): Int {
        if (net || Roster.isUnlocked(index, prefs.careerWins)) return index
        return 0
    }

    private fun handleRoleTap(x: Float, y: Float) {
        if (rectBack.contains(x, y)) { sound.tap(); screen = Screen.MODE; return }
        when {
            roleRects[0].contains(x, y) -> { sound.tap(); startHosting() }
            roleRects[1].contains(x, y) -> { sound.tap(); startJoining() }
        }
    }

    private fun handleDevicesTap(x: Float, y: Float) {
        if (rectBack.contains(x, y)) { sound.tap(); screen = Screen.NET_ROLE; return }
        val r = RectF()
        for (i in btPeers.indices) {
            deviceRect(i, r)
            if (r.contains(x, y)) {
                sound.tap()
                val peer = btPeers[i]
                role = Role.CLIENT; playerSide = 1
                netStatus = "Connecting to ${peer.name}…"
                screen = Screen.NET_CONNECT
                connector = BluetoothLink.join(context, peer.address, ::onNetState, ::onNetLink)
                return
            }
        }
    }

    private fun handleFightOverlayTap(x: Float, y: Float) {
        if (phase != Phase.MATCH_END) return
        when {
            resultRects[0].contains(x, y) -> {
                sound.tap()
                if (role == Role.NONE) {
                    startMatch(Roster.all[myFighterIndex], Roster.all[oppFighterIndex])
                } else {
                    // Networked rematch isn't re-negotiated here; return to menu.
                    teardownNet(); goTitle()
                }
            }
            resultRects[1].contains(x, y) -> { sound.tap(); teardownNet(); goTitle() }
            resultRects[2].contains(x, y) -> shareResult()
        }
    }

    private fun toggleFx() {
        val on = !sound.enabled
        sound.enabled = on; haptics.enabled = on
        prefs.soundEnabled = on; prefs.hapticsEnabled = on
        if (on) { sound.tap(); haptics.light() }
    }

    private fun goTitle() {
        screen = Screen.TITLE
        role = Role.NONE
        playerSide = 0
        netError = null
    }

    // ----------------------------------------------------------------------
    // Networking control
    // ----------------------------------------------------------------------

    private fun startHosting() {
        role = Role.HOST; playerSide = 0
        netError = null
        val begin = {
            if (transport == Transport.WIFI) {
                val ip = WifiLink.localIp() ?: "this device"
                netStatus = "Waiting for a friend…\nSame Wi-Fi / hotspot · $ip"
                connector = WifiLink.host(context, ::onNetState, ::onNetLink)
            } else {
                netStatus = "Waiting for a paired phone to join…"
                connector = BluetoothLink.host(context, ::onNetState, ::onNetLink)
            }
            screen = Screen.NET_CONNECT
        }
        if (transport == Transport.BLUETOOTH) {
            ensureBt { ok -> if (ok) begin() else { netError = "Bluetooth permission denied" } }
        } else begin()
    }

    private fun startJoining() {
        role = Role.CLIENT; playerSide = 1
        netError = null
        if (transport == Transport.WIFI) {
            netStatus = "Searching for a host on Wi-Fi…"
            screen = Screen.NET_CONNECT
            connector = WifiLink.join(context, ::onNetState, ::onNetLink)
        } else {
            ensureBt { ok ->
                if (!ok) { netError = "Bluetooth permission denied"; return@ensureBt }
                if (!BluetoothLink.isAvailable(context)) { netError = "Turn Bluetooth on, then pair the two phones"; return@ensureBt }
                btPeers = BluetoothLink.bondedPeers(context)
                screen = Screen.NET_DEVICES
            }
        }
    }

    private fun ensureBt(cb: (Boolean) -> Unit) {
        val host = context as? GameHost
        if (host == null) cb(false) else host.ensureBluetoothPermission(cb)
    }

    private fun onNetState(state: NetState) {
        when (state) {
            NetState.FAILED -> netError = "Connection failed"
            NetState.CLOSED -> if (screen == Screen.FIGHT || screen == Screen.NET_CHAR) netError = "Opponent left"
            else -> {}
        }
    }

    /** Called once a transport connects. Wires message handling and advances UI. */
    private fun onNetLink(link: NetLink) {
        netLink = link
        link.onMessage = ::onNetMessage
        remoteButtons = 0
        pendingSnapshot = null
        netStartA = -1; netStartB = -1
        if (role == Role.HOST) {
            remoteFighterIndex = 2
            preview = Fighter(Roster.all[myFighterIndex], fighterHeight, groundY).also { it.reset(w / 2f, 1) }
            screen = Screen.NET_CHAR
        } else {
            preview = Fighter(Roster.all[myFighterIndex], fighterHeight, groundY).also { it.reset(w / 2f, 1) }
            netLink?.send(Packet.hello(myFighterIndex))
            screen = Screen.NET_CHAR
        }
    }

    private fun onNetMessage(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            Msg.HELLO -> if (role == Role.HOST && data.size >= 2) {
                remoteFighterIndex = (data[1].toInt() and 0xFF).coerceIn(0, Roster.all.size - 1)
            }
            Msg.INPUT -> if (role == Role.HOST && data.size >= 2) {
                remoteButtons = data[1].toInt() and 0x7F
            }
            Msg.START -> if (role == Role.CLIENT && data.size >= 3) {
                netStartA = (data[1].toInt() and 0xFF).coerceIn(0, Roster.all.size - 1)
                netStartB = (data[2].toInt() and 0xFF).coerceIn(0, Roster.all.size - 1)
            }
            Msg.STATE -> if (role == Role.CLIENT) {
                val s = Snapshot(); try { s.decode(data); pendingSnapshot = s } catch (_: Throwable) {}
            }
            Msg.BYE -> netError = "Opponent left"
        }
    }

    private fun teardownNet(): Unit {
        try { netLink?.send(Packet.bye()) } catch (_: Throwable) {}
        try { connector?.close() } catch (_: Throwable) {}
        try { netLink?.close() } catch (_: Throwable) {}
        connector = null
        netLink = null
        role = Role.NONE
    }

    // ----------------------------------------------------------------------
    // Match flow
    // ----------------------------------------------------------------------

    private fun startMatch(aArch: FighterArchetype, bArch: FighterArchetype) {
        synchronized(holder) {
            fA = Fighter(aArch, fighterHeight, groundY)
            fB = Fighter(bArch, fighterHeight, groundY)
            if (role == Role.NONE) ai = Ai(difficulty).also { it.reset() }
            roundsWonA = 0; roundsWonB = 0; roundNum = 1
            bestComboThisMatch = 0; matchRecorded = false
            comboCount[0] = 0; comboCount[1] = 0; comboPopupTimer = 0
            prevAhp = fA!!.maxHp; prevBhp = fB!!.maxHp
            localButtons = 0; remoteButtons = 0
            netSendAcc = 0
            beginRound()
            screen = Screen.FIGHT
        }
    }

    /** Client-side match init driven by a received START message. */
    private fun startMatchClient(aIdx: Int, bIdx: Int) {
        synchronized(holder) {
            fA = Fighter(Roster.all[aIdx], fighterHeight, groundY).also { it.reset(startXA, 1) }
            fB = Fighter(Roster.all[bIdx], fighterHeight, groundY).also { it.reset(startXB, -1) }
            roundsWonA = 0; roundsWonB = 0; roundNum = 1
            phase = Phase.INTRO; banner = roundBanner(); introTicks = 0
            timerTicks = ROUND_TICKS
            prevAhp = fA!!.maxHp; prevBhp = fB!!.maxHp
            comboPopupTimer = 0
            localButtons = 0
            screen = Screen.FIGHT
        }
    }

    private fun beginRound() {
        fA?.resetForRound(startXA, 1)
        fB?.resetForRound(startXB, -1)
        timerTicks = ROUND_TICKS
        introTicks = 0; endTicks = 0
        roundWinner = -1
        comboCount[0] = 0; comboCount[1] = 0; comboPopupTimer = 0
        particles.clear()
        ai?.reset()
        phase = Phase.INTRO
        banner = roundBanner()
    }

    private fun roundBanner() = roundNum.coerceIn(1, 3)

    // ----------------------------------------------------------------------
    // Simulation
    // ----------------------------------------------------------------------

    private fun update() {
        bgPhase += 1f
        particles.update()
        if (shakeTime > 0) shakeTime--
        if (flash[0] > 0) flash[0]--
        if (flash[1] > 0) flash[1]--

        if (screen == Screen.CHAR || screen == Screen.NET_CHAR) {
            preview?.update(0, w, leftBound, rightBound)
        }

        if (role == Role.CLIENT) { clientUpdate(); return }
        if (screen != Screen.FIGHT) return
        simulate()
        if (role == Role.HOST) netSendState()
    }

    private fun simulate() {
        val a = fA ?: return
        val b = fB ?: return
        when (phase) {
            Phase.INTRO -> {
                a.update(0, b.x, leftBound, rightBound)
                b.update(0, a.x, leftBound, rightBound)
                introTicks++
                banner = if (introTicks < INTRO_NAME_TICKS) roundBanner() else BANNER_FIGHT
                if (introTicks >= INTRO_TOTAL_TICKS) { phase = Phase.FIGHTING; banner = 0; sound.bell() }
            }
            Phase.FIGHTING -> {
                val inputA = localButtons
                val inputB = if (role == Role.HOST) remoteButtons else ai?.nextButtons(b, a) ?: 0
                a.update(inputA, b.x, leftBound, rightBound)
                b.update(inputB, a.x, leftBound, rightBound)

                consumeStart(a, 0)
                consumeStart(b, 1)
                resolveHits()

                if (a.isNeutral()) comboCount[1] = 0
                if (b.isNeutral()) comboCount[0] = 0
                if (comboPopupTimer > 0) comboPopupTimer--

                timerTicks--
                checkRoundEnd(a, b)
            }
            Phase.ROUND_END -> {
                a.update(0, b.x, leftBound, rightBound)
                b.update(0, a.x, leftBound, rightBound)
                endTicks++
                if (endTicks >= ROUND_END_TICKS) advanceRound()
            }
            Phase.MATCH_END -> {
                a.update(0, b.x, leftBound, rightBound)
                b.update(0, a.x, leftBound, rightBound)
            }
        }
    }

    private fun consumeStart(f: Fighter, side: Int) {
        val code = f.consumeStartedAttack()
        if (code == 0) return
        if (side == 0) frameStartedA = code else frameStartedB = code
        playStartSfx(code)
    }

    private fun playStartSfx(code: Int) {
        when (code) {
            1, 2 -> sound.whoosh()
            3 -> sound.special()
        }
    }

    private fun resolveHits() {
        val a = fA ?: return
        val b = fB ?: return
        tryHit(a, b, 0)
        tryHit(b, a, 1)
    }

    private fun tryHit(attacker: Fighter, defender: Fighter, attackerSide: Int) {
        if (!attacker.canHit() || !defender.canBeHit()) return
        if (!attacker.attackBox(boxA)) return
        defender.hurtBox(boxB)
        if (!RectF.intersects(boxA, boxB)) return

        val wasStunned = defender.isStunned()
        val result = defender.takeHit(attacker)
        attacker.markHit()

        val sparkX = defender.hitSparkX()
        val sparkY = defender.hitSparkY()
        val toward = if (attacker.x <= defender.x) 0f else Math.PI.toFloat()

        when (result) {
            Fighter.HitResult.BLOCKED -> {
                sound.block(); haptics.light()
                particles.spark(sparkX, sparkY, 0xFFBFE9FF.toInt(), 8, fighterHeight * 0.05f, toward, 0.6f)
                addShake(fighterHeight * 0.01f, 4)
            }
            Fighter.HitResult.HIT, Fighter.HitResult.KO -> {
                attacker.addMeter(attacker.currentMoveMeterGain())
                comboCount[attackerSide] = if (wasStunned) comboCount[attackerSide] + 1 else 1
                if (comboCount[attackerSide] >= 2) {
                    comboPopupTimer = COMBO_POPUP_TICKS
                    comboShownCount = comboCount[attackerSide]
                    comboSide = attackerSide
                }
                bestComboThisMatch = max(bestComboThisMatch, comboCount[attackerSide])
                flash[1 - attackerSide] = 5
                val color = defender.archetype.body
                val big = attacker.currentMove() == 3 || result == Fighter.HitResult.KO
                particles.spark(sparkX, sparkY, color, if (big) 26 else 14, fighterHeight * (if (big) 0.11f else 0.07f), toward, 0.9f)
                particles.spark(sparkX, sparkY, 0xFFFFFFFF.toInt(), 6, fighterHeight * 0.05f, toward, 1.0f)
                when (attacker.currentMove()) {
                    1 -> sound.punch()
                    2 -> sound.kick()
                }
                haptics.hit()
                addShake(fighterHeight * (if (big) 0.04f else 0.018f), if (big) 10 else 6)
                if (result == Fighter.HitResult.KO) { sound.ko(); haptics.heavy(); addShake(fighterHeight * 0.05f, 16) }
            }
        }
    }

    private fun checkRoundEnd(a: Fighter, b: Fighter) {
        if (!a.isAlive || !b.isAlive) {
            roundWinner = when {
                !a.isAlive && !b.isAlive -> -1
                !a.isAlive -> 1
                else -> 0
            }
            banner = BANNER_KO
            endRound(a, b)
        } else if (timerTicks <= 0) {
            roundWinner = when {
                a.hp > b.hp -> 0
                b.hp > a.hp -> 1
                else -> -1
            }
            banner = BANNER_TIME
            endRound(a, b)
        }
    }

    private fun endRound(a: Fighter, b: Fighter) {
        phase = Phase.ROUND_END
        endTicks = 0
        if (roundWinner == 0) a.setWinPose()
        if (roundWinner == 1) b.setWinPose()
    }

    private fun advanceRound() {
        if (roundWinner == 0) roundsWonA++ else if (roundWinner == 1) roundsWonB++
        if (roundsWonA >= WIN_TARGET || roundsWonB >= WIN_TARGET || roundNum >= MAX_ROUNDS) {
            endMatch()
        } else {
            roundNum++
            beginRound()
        }
    }

    private fun endMatch() {
        phase = Phase.MATCH_END
        val winnerA = roundsWonA >= roundsWonB
        banner = if (winnerA) BANNER_WIN_A else BANNER_WIN_B
        val playerWon = (playerSide == 0 && winnerA) || (playerSide == 1 && !winnerA)
        if (playerWon) sound.win() else sound.ko()
        if (!matchRecorded) {
            matchRecorded = true
            if (role == Role.NONE) prefs.recordResult(playerWon)
            prefs.submitCombo(bestComboThisMatch)
        }
    }

    // ----------------------------------------------------------------------
    // Client update (renders host snapshots, sends its inputs)
    // ----------------------------------------------------------------------

    private fun clientUpdate() {
        val sa = netStartA; val sb = netStartB
        if (sa >= 0 && sb >= 0 && screen != Screen.FIGHT) {
            startMatchClient(sa, sb)
            netStartA = -1; netStartB = -1
        }
        if (screen != Screen.FIGHT) return

        pendingSnapshot?.let { applySnapshot(it); pendingSnapshot = null }
        netLink?.send(Packet.input(localButtons))
    }

    private fun applySnapshot(s: Snapshot) {
        val a = fA ?: return
        val b = fB ?: return
        a.applyNet(s.ax, s.ay, s.afacing, s.astate, s.atime, s.ahp, s.ameter)
        b.applyNet(s.bx, s.by, s.bfacing, s.bstate, s.btime, s.bhp, s.bmeter)
        phase = Phase.values().getOrElse(s.phase) { Phase.FIGHTING }
        roundNum = s.roundNum
        roundsWonA = s.wonA; roundsWonB = s.wonB
        timerTicks = s.timer * 60
        banner = s.banner
        comboPopupTimer = if (s.comboShow > 0) COMBO_POPUP_TICKS else 0
        comboShownCount = s.comboShow; comboSide = s.comboSide

        playStartSfx(s.startedA); playStartSfx(s.startedB)
        // Approximate hit feedback from health drops.
        reactToDamage(s.ahp, prevAhp, 1); reactToDamage(s.bhp, prevBhp, 0)
        prevAhp = s.ahp; prevBhp = s.bhp
    }

    private fun reactToDamage(hp: Float, prev: Float, attackerSide: Int) {
        val drop = prev - hp
        if (drop <= 0.5f) return
        val spark = if (attackerSide == 0) fB else fA
        spark?.let {
            particles.spark(it.hitSparkX(), it.hitSparkY(), it.archetype.body, 14, fighterHeight * 0.07f, 0f, 1.0f)
        }
        when {
            drop >= 18f -> { sound.kick(); addShake(fighterHeight * 0.04f, 10) }
            drop >= 6f -> { sound.punch(); addShake(fighterHeight * 0.02f, 6) }
            else -> sound.block()
        }
        haptics.hit()
    }

    private fun netSendState() {
        if (phase != Phase.FIGHTING && phase != Phase.INTRO && phase != Phase.ROUND_END && phase != Phase.MATCH_END) return
        netSendAcc++
        if (netSendAcc < NET_SEND_EVERY) return
        netSendAcc = 0
        val a = fA ?: return
        val b = fB ?: return
        val s = Snapshot()
        s.ax = a.x; s.ay = a.y; s.afacing = a.facing; s.astate = a.state.ordinal; s.atime = a.stateTime.coerceIn(0, 30000)
        s.ahp = a.hp; s.ameter = a.meter
        s.bx = b.x; s.by = b.y; s.bfacing = b.facing; s.bstate = b.state.ordinal; s.btime = b.stateTime.coerceIn(0, 30000)
        s.bhp = b.hp; s.bmeter = b.meter
        s.phase = phase.ordinal; s.roundNum = roundNum; s.wonA = roundsWonA; s.wonB = roundsWonB
        s.timer = (timerTicks / 60).coerceIn(0, 999)
        s.comboShow = if (comboPopupTimer > 0) comboShownCount else 0
        s.comboSide = comboSide; s.banner = banner
        s.startedA = frameStartedA; s.startedB = frameStartedB
        frameStartedA = 0; frameStartedB = 0
        netLink?.send(s.encode())
    }

    private fun addShake(mag: Float, ticks: Int) {
        shakeMag = max(shakeMag, mag); shakeTime = max(shakeTime, ticks)
    }

    // ----------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------

    private fun drawAll(canvas: Canvas) {
        canvas.drawPaint(skyPaint.also { it.style = Paint.Style.FILL })
        when (screen) {
            Screen.TITLE -> drawTitle(canvas)
            Screen.MODE -> drawMode(canvas)
            Screen.DIFFICULTY -> drawDifficulty(canvas)
            Screen.CHAR -> drawCharSelect(canvas, net = false)
            Screen.NET_ROLE -> drawNetRole(canvas)
            Screen.NET_DEVICES -> drawDevices(canvas)
            Screen.NET_CONNECT -> drawConnecting(canvas)
            Screen.NET_CHAR -> drawCharSelect(canvas, net = true)
            Screen.FIGHT -> drawFight(canvas)
        }
        netError?.let { drawErrorOverlay(canvas, it) }
    }

    private fun drawStage(canvas: Canvas) {
        // Distant arena floor + a simple crowd band for depth.
        barPaint.color = Color.parseColor("#241830")
        canvas.drawRect(0f, groundY - h * 0.10f, w, groundY, barPaint)
        barPaint.color = Color.parseColor("#3A2A12")
        canvas.drawRect(0f, groundY, w, h, barPaint)
        barPaint.color = Color.parseColor("#5A4018")
        canvas.drawRect(0f, groundY, w, groundY + h * 0.012f, barPaint)
        // Crowd specks.
        barPaint.color = 0x33FFFFFF
        var i = 0
        var x = w * 0.04f
        while (x < w) {
            val yy = groundY - h * 0.085f + ((i % 3) * h * 0.02f)
            canvas.drawCircle(x, yy, h * 0.008f, barPaint)
            x += w * 0.05f; i++
        }
    }

    private fun drawFight(canvas: Canvas) {
        val a = fA
        val b = fB
        val sx = if (shakeTime > 0) (Random.nextFloat() * 2f - 1f) * shakeMag else 0f
        val sy = if (shakeTime > 0) (Random.nextFloat() * 2f - 1f) * shakeMag else 0f
        canvas.save()
        canvas.translate(sx, sy)
        drawStage(canvas)
        a?.draw(canvas, flash[0] > 0)
        b?.draw(canvas, flash[1] > 0)
        particles.draw(canvas)
        canvas.restore()

        if (a != null && b != null) drawHud(canvas, a, b)
        if (phase == Phase.FIGHTING) drawControls(canvas)
        drawBanner(canvas)
        if (phase == Phase.MATCH_END) drawResult(canvas)
    }

    private fun drawHud(canvas: Canvas, a: Fighter, b: Fighter) {
        val barTop = h * 0.05f
        val barH = h * 0.045f
        val barW = w * 0.40f
        // Health.
        healthBar(canvas, w * 0.04f, barTop, barW, barH, a.hp / a.maxHp, true, 0xFF3BE0A0.toInt())
        healthBar(canvas, w * 0.96f - barW, barTop, barW, barH, b.hp / b.maxHp, false, 0xFFFF6B6B.toInt())
        // Meter.
        val mTop = barTop + barH + h * 0.012f
        val mH = h * 0.018f
        meterBar(canvas, w * 0.04f, mTop, barW * 0.7f, mH, a.meter / Fighter.METER_MAX, true)
        meterBar(canvas, w * 0.96f - barW * 0.7f, mTop, barW * 0.7f, mH, b.meter / Fighter.METER_MAX, false)

        // Names + who's "YOU".
        textPaint.textSize = h * 0.035f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        canvas.drawText(a.archetype.name + (if (playerSide == 0) "  (YOU)" else ""), w * 0.04f, barTop - h * 0.012f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText((if (playerSide == 1) "(YOU)  " else "") + b.archetype.name, w * 0.96f, barTop - h * 0.012f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // Timer.
        textPaint.color = if (timerTicks <= 600) Color.parseColor("#FF6B6B") else Color.WHITE
        textPaint.textSize = h * 0.075f
        canvas.drawText((timerTicks / 60).coerceAtLeast(0).toString(), w / 2f, barTop + h * 0.06f, textPaint)

        // Round pips.
        drawPips(canvas, w / 2f - w * 0.05f, mTop + h * 0.01f, roundsWonA, true)
        drawPips(canvas, w / 2f + w * 0.05f, mTop + h * 0.01f, roundsWonB, false)

        // Combo popup.
        if (comboPopupTimer > 0 && comboShownCount >= 2) {
            val px = if (comboSide == 0) w * 0.30f else w * 0.70f
            textPaint.color = Color.parseColor("#FFD25A")
            textPaint.textSize = h * 0.06f + (COMBO_POPUP_TICKS - comboPopupTimer).coerceAtMost(10) * 0.0f
            canvas.drawText("x$comboShownCount COMBO", px, h * 0.30f, textPaint)
        }
    }

    private fun healthBar(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, frac: Float, alignLeft: Boolean, color: Int) {
        barPaint.color = 0xCC10060F.toInt()
        canvas.drawRoundRect(left, top, left + width, top + height, height * 0.3f, height * 0.3f, barPaint)
        val f = frac.coerceIn(0f, 1f)
        barPaint.color = color
        if (alignLeft) {
            canvas.drawRoundRect(left, top, left + width * f, top + height, height * 0.3f, height * 0.3f, barPaint)
        } else {
            canvas.drawRoundRect(left + width * (1f - f), top, left + width, top + height, height * 0.3f, height * 0.3f, barPaint)
        }
        strokePaint.color = 0x66FFFFFF; strokePaint.strokeWidth = max(2f, h * 0.004f)
        canvas.drawRoundRect(left, top, left + width, top + height, height * 0.3f, height * 0.3f, strokePaint)
    }

    private fun meterBar(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, frac: Float, alignLeft: Boolean) {
        barPaint.color = 0xAA000000.toInt()
        canvas.drawRoundRect(left, top, left + width, top + height, height * 0.5f, height * 0.5f, barPaint)
        val f = frac.coerceIn(0f, 1f)
        barPaint.color = if (f >= 1f) Color.parseColor("#FFEFA0") else Color.parseColor("#FFB020")
        if (alignLeft) canvas.drawRoundRect(left, top, left + width * f, top + height, height * 0.5f, height * 0.5f, barPaint)
        else canvas.drawRoundRect(left + width * (1f - f), top, left + width, top + height, height * 0.5f, height * 0.5f, barPaint)
    }

    private fun drawPips(canvas: Canvas, cx: Float, cy: Float, won: Int, leftward: Boolean) {
        val r = h * 0.012f
        for (i in 0 until WIN_TARGET) {
            val dx = (i * r * 3f) * (if (leftward) -1f else 1f)
            barPaint.color = if (i < won) Color.parseColor("#FFD25A") else 0x44FFFFFF
            canvas.drawCircle(cx + dx, cy, r, barPaint)
        }
    }

    private fun drawControls(canvas: Canvas) {
        for (c in controls) {
            val pressed = Btn.has(localButtons, c.btn)
            val special = c.btn == Btn.SPECIAL
            val ready = special && (fByPlayer()?.meterFull == true)
            barPaint.color = when {
                ready -> 0xCCFFD25A.toInt()
                pressed -> 0x88FFFFFF.toInt()
                else -> 0x44FFFFFF
            }
            canvas.drawCircle(c.cx, c.cy, c.r, barPaint)
            strokePaint.color = 0x66FFFFFF; strokePaint.strokeWidth = max(2f, h * 0.004f)
            canvas.drawCircle(c.cx, c.cy, c.r, strokePaint)
            textPaint.color = if (ready) Color.parseColor("#3A2400") else Color.WHITE
            textPaint.textSize = c.r * 0.7f
            canvas.drawText(labelFor(c.btn), c.cx, c.cy + c.r * 0.25f, textPaint)
        }
    }

    private fun fByPlayer(): Fighter? = if (playerSide == 0) fA else fB

    private fun labelFor(btn: Int): String = when (btn) {
        Btn.LEFT -> "‹"
        Btn.RIGHT -> "›"
        Btn.JUMP -> "▲"
        Btn.PUNCH -> "P"
        Btn.KICK -> "K"
        Btn.BLOCK -> "B"
        Btn.SPECIAL -> "★"
        else -> ""
    }

    private fun drawBanner(canvas: Canvas) {
        val text = bannerText() ?: return
        textPaint.color = when (banner) {
            BANNER_KO -> Color.parseColor("#FF4D5B")
            BANNER_FIGHT -> Color.parseColor("#FFD25A")
            BANNER_WIN_A, BANNER_WIN_B -> Color.parseColor("#22E0C8")
            else -> Color.WHITE
        }
        textPaint.textSize = h * 0.14f
        canvas.drawText(text, w / 2f, h * 0.42f, textPaint)
    }

    private fun bannerText(): String? = when (banner) {
        1 -> "ROUND 1"
        2 -> "ROUND 2"
        3 -> "ROUND 3"
        BANNER_FIGHT -> "FIGHT!"
        BANNER_KO -> "K.O."
        BANNER_TIME -> "TIME UP"
        BANNER_WIN_A -> if (playerSide == 0) "YOU WIN" else "YOU LOSE"
        BANNER_WIN_B -> if (playerSide == 1) "YOU WIN" else "YOU LOSE"
        else -> null
    }

    private fun drawResult(canvas: Canvas) {
        canvas.drawRect(0f, h * 0.50f, w, h, dimPaint)
        val winnerA = roundsWonA >= roundsWonB
        val youWon = (playerSide == 0 && winnerA) || (playerSide == 1 && !winnerA)
        textPaint.color = 0xAAFFFFFF.toInt()
        textPaint.textSize = h * 0.04f
        canvas.drawText("$roundsWonA  –  $roundsWonB   •   best combo x$bestComboThisMatch", w / 2f, h * 0.40f, textPaint)
        drawButton(canvas, resultRects[0], if (role == Role.NONE) "REMATCH" else "MENU", Color.parseColor("#22E0C8"), Color.parseColor("#06231F"))
        drawButton(canvas, resultRects[1], "MAIN MENU", Color.parseColor("#3A2A12"), Color.WHITE)
        drawButton(canvas, resultRects[2], "SHARE", Color.parseColor("#2C2030"), Color.WHITE)
        // The verdict banner is already drawn by drawBanner; keep result tidy.
        if (!youWon) { /* verdict handled by banner */ }
    }

    // --- Menus ---

    private fun drawTitle(canvas: Canvas) {
        textPaint.color = Color.parseColor("#FFD25A")
        textPaint.textSize = h * 0.18f
        canvas.drawText("DHISHOOM", w / 2f, h * 0.34f, textPaint)
        textPaint.color = 0x99FFFFFF.toInt()
        textPaint.textSize = h * 0.045f
        canvas.drawText("quick fight · best of 3 · no internet", w / 2f, h * 0.43f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.05f
        val pulse = 0.5f + 0.5f * kotlin.math.sin(bgPhase * 0.07f)
        textPaint.alpha = (140 + 115 * pulse).toInt()
        canvas.drawText("TAP TO PLAY", w / 2f, h * 0.62f, textPaint)
        textPaint.alpha = 255

        textPaint.color = 0x88FFFFFF.toInt()
        textPaint.textSize = h * 0.04f
        canvas.drawText("wins ${prefs.careerWins}   losses ${prefs.careerLosses}   best combo x${prefs.bestCombo}", w / 2f, h * 0.80f, textPaint)

        drawButton(canvas, rectFx, if (sound.enabled) "FX ON" else "FX OFF",
            if (sound.enabled) Color.parseColor("#2C2030") else Color.parseColor("#3A1A1A"),
            if (sound.enabled) Color.WHITE else 0x77FFFFFF.toInt())
    }

    private fun drawMode(canvas: Canvas) {
        title(canvas, "CHOOSE MODE")
        drawButton(canvas, modeRects[0], "SOLO  vs  BOT", Color.parseColor("#22E0C8"), Color.parseColor("#06231F"))
        drawButton(canvas, modeRects[1], "WI-FI / HOTSPOT", Color.parseColor("#FFB020"), Color.parseColor("#2A1A00"))
        drawButton(canvas, modeRects[2], "BLUETOOTH", Color.parseColor("#9B5DE5"), Color.WHITE)
        drawButton(canvas, rectBack, "BACK", Color.parseColor("#2C2030"), Color.WHITE)
    }

    private fun drawDifficulty(canvas: Canvas) {
        title(canvas, "DIFFICULTY")
        drawButton(canvas, diffRects[0], "EASY", Color.parseColor("#3BE0A0"), Color.parseColor("#06231F"))
        drawButton(canvas, diffRects[1], "NORMAL", Color.parseColor("#FFB020"), Color.parseColor("#2A1A00"))
        drawButton(canvas, diffRects[2], "HARD", Color.parseColor("#FF4D5B"), Color.WHITE)
        drawButton(canvas, rectBack, "BACK", Color.parseColor("#2C2030"), Color.WHITE)
    }

    private fun drawNetRole(canvas: Canvas) {
        title(canvas, if (transport == Transport.WIFI) "WI-FI / HOTSPOT" else "BLUETOOTH")
        drawButton(canvas, roleRects[0], "HOST A FIGHT", Color.parseColor("#22E0C8"), Color.parseColor("#06231F"))
        drawButton(canvas, roleRects[1], "JOIN A FIGHT", Color.parseColor("#FFB020"), Color.parseColor("#2A1A00"))
        drawButton(canvas, rectBack, "BACK", Color.parseColor("#2C2030"), Color.WHITE)
        textPaint.color = 0x88FFFFFF.toInt(); textPaint.textSize = h * 0.038f
        val hint = if (transport == Transport.WIFI)
            "Both phones on the same Wi-Fi, or one hosting a hotspot the other joined."
        else
            "Pair the two phones in Android Bluetooth settings first."
        canvas.drawText(hint, w / 2f, h * 0.92f, textPaint)
    }

    private fun drawDevices(canvas: Canvas) {
        title(canvas, "PICK A PAIRED PHONE")
        if (btPeers.isEmpty()) {
            textPaint.color = 0xAAFFFFFF.toInt(); textPaint.textSize = h * 0.05f
            canvas.drawText("No paired devices.", w / 2f, h * 0.45f, textPaint)
            textPaint.textSize = h * 0.038f; textPaint.color = 0x88FFFFFF.toInt()
            canvas.drawText("Pair the phones in Bluetooth settings, then come back.", w / 2f, h * 0.52f, textPaint)
        } else {
            val r = RectF()
            for (i in btPeers.indices) {
                deviceRect(i, r)
                drawButton(canvas, r, btPeers[i].name, Color.parseColor("#2C2030"), Color.WHITE)
            }
        }
        drawButton(canvas, rectBack, "BACK", Color.parseColor("#2C2030"), Color.WHITE)
    }

    private fun deviceRect(i: Int, out: RectF) {
        val bw = w * 0.5f; val bh = h * 0.10f; val gap = h * 0.03f
        val top = h * 0.28f + i * (bh + gap)
        out.set(w / 2f - bw / 2f, top, w / 2f + bw / 2f, top + bh)
    }

    private fun drawConnecting(canvas: Canvas) {
        title(canvas, if (role == Role.HOST) "HOSTING" else "JOINING")
        textPaint.color = Color.WHITE; textPaint.textSize = h * 0.05f
        var y = h * 0.45f
        for (line in netStatus.split("\n")) {
            canvas.drawText(line, w / 2f, y, textPaint)
            y += h * 0.07f
        }
        val dots = ".".repeat(((bgPhase / 20f).toInt() % 4))
        textPaint.color = 0x88FFFFFF.toInt()
        canvas.drawText(dots, w / 2f, y + h * 0.02f, textPaint)
        drawButton(canvas, rectBack, "CANCEL", Color.parseColor("#3A1A1A"), Color.WHITE)
    }

    private fun drawCharSelect(canvas: Canvas, net: Boolean) {
        title(canvas, "CHOOSE YOUR FIGHTER")
        val arch = Roster.all[myFighterIndex]
        preview?.let {
            canvas.save(); canvas.translate(0f, h * 0.05f); it.draw(canvas, false); canvas.restore()
        }
        textPaint.color = arch.body; textPaint.textSize = h * 0.07f
        canvas.drawText(arch.name, w / 2f, h * 0.30f, textPaint)
        textPaint.color = 0xAAFFFFFF.toInt(); textPaint.textSize = h * 0.038f
        canvas.drawText(arch.tagline, w / 2f, h * 0.36f, textPaint)

        // Stat bars.
        statBar(canvas, "SPD", arch.speedMul, h * 0.66f)
        statBar(canvas, "POW", arch.powerMul, h * 0.72f)
        statBar(canvas, "VIT", arch.healthMul, h * 0.78f)

        drawButton(canvas, rectArrowL, "‹", Color.parseColor("#2C2030"), Color.WHITE)
        drawButton(canvas, rectArrowR, "›", Color.parseColor("#2C2030"), Color.WHITE)
        drawButton(canvas, rectBack, "BACK", Color.parseColor("#2C2030"), Color.WHITE)

        if (!net) {
            drawButton(canvas, rectConfirm, "FIGHT!", Color.parseColor("#22E0C8"), Color.parseColor("#06231F"))
        } else if (role == Role.HOST) {
            val oppName = Roster.all[remoteFighterIndex.coerceIn(0, Roster.all.size - 1)].name
            textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.04f
            canvas.drawText("opponent: $oppName", w / 2f, h * 0.50f, textPaint)
            drawButton(canvas, rectConfirm, "START FIGHT", Color.parseColor("#22E0C8"), Color.parseColor("#06231F"))
        } else {
            textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.04f
            canvas.drawText("waiting for host to start…", w / 2f, h * 0.50f, textPaint)
            drawButton(canvas, rectConfirm, "READY", Color.parseColor("#2C2030"), 0x88FFFFFF.toInt())
        }
    }

    private fun statBar(canvas: Canvas, label: String, mul: Float, cy: Float) {
        val left = w * 0.32f; val width = w * 0.30f; val height = h * 0.03f
        textPaint.textAlign = Paint.Align.RIGHT; textPaint.color = 0xAAFFFFFF.toInt(); textPaint.textSize = h * 0.035f
        canvas.drawText(label, left - w * 0.02f, cy + height * 0.8f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        barPaint.color = 0x33FFFFFF
        canvas.drawRoundRect(left, cy, left + width, cy + height, height / 2f, height / 2f, barPaint)
        val f = ((mul - 0.7f) / 0.7f).coerceIn(0.1f, 1f)
        barPaint.color = Color.parseColor("#FFD25A")
        canvas.drawRoundRect(left, cy, left + width * f, cy + height, height / 2f, height / 2f, barPaint)
    }

    private fun title(canvas: Canvas, t: String) {
        textPaint.color = Color.parseColor("#FFD25A"); textPaint.textSize = h * 0.07f
        canvas.drawText(t, w / 2f, h * 0.16f, textPaint)
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, fill: Int, txt: Int) {
        barPaint.color = fill
        val radius = r.height() / 2.4f
        canvas.drawRoundRect(r, radius, radius, barPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = txt
        textPaint.textSize = r.height() * 0.42f
        canvas.drawText(label, r.centerX(), r.centerY() + r.height() * 0.15f, textPaint)
    }

    private fun drawErrorOverlay(canvas: Canvas, msg: String) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.color = Color.parseColor("#FF6B6B"); textPaint.textSize = h * 0.07f
        canvas.drawText(msg, w / 2f, h * 0.45f, textPaint)
        textPaint.color = Color.WHITE; textPaint.textSize = h * 0.045f
        canvas.drawText("tap to return to menu", w / 2f, h * 0.55f, textPaint)
    }

    private fun shareResult() {
        val a = fA ?: return
        val b = fB ?: return
        val mine = if (playerSide == 0) a.archetype else b.archetype
        val theirs = if (playerSide == 0) b.archetype else a.archetype
        val myRounds = if (playerSide == 0) roundsWonA else roundsWonB
        val theirRounds = if (playerSide == 0) roundsWonB else roundsWonA
        val won = myRounds > theirRounds
        val mode = when (role) {
            Role.NONE -> "SOLO vs BOT"
            else -> if (transport == Transport.WIFI) "WI-FI VERSUS" else "BLUETOOTH VERSUS"
        }
        ShareCard.share(context, won, mine, theirs, myRounds, theirRounds, bestComboThisMatch, mode)
    }

    // ----------------------------------------------------------------------
    // Render thread
    // ----------------------------------------------------------------------

    private inner class GameThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        @Volatile var running = false
        private val targetFrameMs = 1000L / 60L

        override fun run() {
            while (running) {
                val frameStart = System.currentTimeMillis()
                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null && w > 0f) {
                        synchronized(surfaceHolder) {
                            update()
                            drawAll(canvas)
                        }
                    }
                } finally {
                    if (canvas != null) {
                        try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
                    }
                }
                val elapsed = System.currentTimeMillis() - frameStart
                val sleep = targetFrameMs - elapsed
                if (sleep > 0) {
                    try { sleep(sleep) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    companion object {
        private const val WIN_TARGET = 2
        private const val MAX_ROUNDS = 5
        private const val ROUND_TICKS = 60 * 60
        private const val INTRO_NAME_TICKS = 60
        private const val INTRO_TOTAL_TICKS = 105
        private const val ROUND_END_TICKS = 120
        private const val COMBO_POPUP_TICKS = 90
        private const val NET_SEND_EVERY = 2

        private const val BANNER_FIGHT = 4
        private const val BANNER_KO = 5
        private const val BANNER_TIME = 6
        private const val BANNER_WIN_A = 7
        private const val BANNER_WIN_B = 8
    }
}
