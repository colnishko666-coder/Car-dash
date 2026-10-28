package com.example.dash

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

// Обороты на 1 км/ч для каждой передачи (колесо 175/70R13, ГП 4.3, 5-ступенчатая КПП).
// Если передача определяется неверно, подправьте числа под свою машину.
private val GEARS = floatArrayOf(138f, 79f, 51f, 37.7f, 31f)

class MainActivity : Activity() {
    private lateinit var dash: DashView
    private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    @Volatile private var session = 0
    private val prefs by lazy { getSharedPreferences("dash", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        dash = DashView(this)
        dash.trip = prefs.getFloat("trip", 0f)
        setContentView(dash)

        // Долгое нажатие: режим HUD. Двойное нажатие: выбрать другой адаптер.
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) { dash.scaleX = -dash.scaleX }
            override fun onDoubleTap(e: MotionEvent): Boolean { chooseAndStart(); return true }
        })
        dash.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        else begin()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) begin()
        else { dash.status = "Разрешите «Устройства поблизости» в настройках приложения"; dash.postInvalidate() }
    }

    private fun begin() {
        val addr = prefs.getString("addr", null)
        val proto = prefs.getString("proto", null)
        if (addr != null && proto != null) connectLoop(addr, proto) else chooseAndStart()
    }

    private fun chooseAndStart() {
        val devs = getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
        if (devs.isEmpty()) {
            dash.status = "Сначала спарьте адаптер в настройках Bluetooth (PIN 1234 или 0000)"
            dash.postInvalidate(); return
        }
        AlertDialog.Builder(this).setTitle("Адаптер")
            .setItems(devs.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                val names = arrayOf("Авто (0)", "ISO 9141-2 (3)", "KWP 5 baud (4)", "KWP fast (5)")
                val codes = arrayOf("0", "3", "4", "5")
                AlertDialog.Builder(this).setTitle("Протокол (как в Car Scanner)")
                    .setItems(names) { _, j ->
                        prefs.edit().putString("addr", devs[i].address).putString("proto", codes[j]).apply()
                        connectLoop(devs[i].address, codes[j])
                    }.show()
            }.show()
    }

    private fun connectLoop(addr: String, proto: String) {
        try { socket?.close() } catch (_: Exception) {}
        val my = ++session
        running = true
        Thread {
            while (running && my == session) {
                try {
                    dash.status = "Подключение..."; dash.postInvalidate()
                    val ad = getSystemService(BluetoothManager::class.java).adapter
                    ad.cancelDiscovery()
                    val s = ad.getRemoteDevice(addr).createRfcommSocketToServiceRecord(SPP)
                    s.connect(); socket = s
                    val inp = s.inputStream; val out = s.outputStream
                    for (c in listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATS0", "ATSP$proto")) cmd(inp, out, c)
                    cmd(inp, out, "0100")
                    dash.status = ""
                    poll(inp, out, my)
                } catch (e: Exception) {
                    dash.status = "Нет связи, повтор..."; dash.postInvalidate()
                }
                try { socket?.close() } catch (_: Exception) {}
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    private fun poll(inp: InputStream, out: OutputStream, my: Int) {
        var sum = 0L; var n = 0L; var loop = 0; var noData = 0
        val fails = IntArray(3)
        var last = System.nanoTime()
        while (running && my == session) {
            val v = pid(inp, out, "010D", 2)
            val r = pid(inp, out, "010C", 4)
            if (v == null && r == null) { if (++noData >= 5) throw IOException("нет ответа ЭБУ") } else noData = 0
            v?.let {
                dash.speed = it
                if (it > dash.maxSpeed) dash.maxSpeed = it
                if (it > 0) { sum += it; n++; dash.avg = sum.toFloat() / n }
            }
            r?.let { dash.rpm = it / 4 }
            if (loop % 10 == 0) {
                if (fails[0] < 3) pid(inp, out, "0105", 2)?.let { dash.temp = it - 40 } ?: fails[0]++
                if (fails[1] < 3) pid(inp, out, "010F", 2)?.let { dash.outTemp = it - 40 } ?: fails[1]++
                if (fails[2] < 3) pid(inp, out, "012F", 2)?.let { dash.fuel = it / 255f } ?: fails[2]++
            }
            val now = System.nanoTime()
            dash.trip += dash.speed * ((now - last) / 1e9f) / 3600f
            last = now
            dash.gear = guessGear(dash.speed, dash.rpm)
            dash.postInvalidate()
            loop++
        }
    }

    private fun guessGear(v: Int, rpm: Int): Int {
        if (v < 3) return 0
        val ratio = rpm / v.toFloat()
        var best = 1; var bd = Float.MAX_VALUE
        GEARS.forEachIndexed { i, k -> val d = abs(ln(ratio / k)); if (d < bd) { bd = d; best = i + 1 } }
        return best
    }

    private fun cmd(inp: InputStream, out: OutputStream, s: String): String {
        out.write((s + "\r").toByteArray()); out.flush()
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0 || c == '>'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun pid(inp: InputStream, out: OutputStream, q: String, hexLen: Int): Int? {
        val r = cmd(inp, out, q).replace("SEARCHING...", "").replace(Regex("\\s"), "").uppercase()
        val key = "41" + q.substring(2)
        val i = r.indexOf(key)
        if (i < 0 || r.length < i + key.length + hexLen) return null
        return r.substring(i + key.length, i + key.length + hexLen).toIntOrNull(16)
    }

    override fun onPause() {
        prefs.edit().putFloat("trip", dash.trip).apply()
        super.onPause()
    }

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

/* ======================  ПАНЕЛЬ  ====================== */

private const val DW = 1916f
private const val DH = 821f

private class Dial(
    val cx: Float, val cy: Float, val max: Float, val major: Float, val minor: Float,
    val sub: Float, val label: Float, val tail: Float, stops: List<Pair<Float, String>>
) {
    private val colors = IntArray(stops.size + 1)
    private val pos = FloatArray(stops.size + 1)
    val rect = RectF(cx - 232, cy - 232, cx + 232, cy + 232)
    val rectOuter = RectF(cx - 252, cy - 252, cx + 252, cy + 252)
    init {
        val k = 255f / 360f
        stops.forEachIndexed { i, (p, c) -> colors[i] = Color.parseColor(c); pos[i] = p * k }
        colors[stops.size] = colors[stops.size - 1]; pos[stops.size] = 1f
    }
    val shader: Shader by lazy {
        SweepGradient(cx, cy, colors, pos).also { s -> s.setLocalMatrix(Matrix().apply { postRotate(135f, cx, cy) }) }
    }
    fun ang(v: Float) = Math.toRadians(135.0 + 255.0 * v / max).toFloat()
}

class DashView(ctx: Context) : View(ctx) {
    @Volatile var speed = 0
    @Volatile var rpm = 0
    @Volatile var temp = 0
    @Volatile var maxSpeed = 0
    @Volatile var avg = 0f
    @Volatile var trip = 0f
    @Volatile var gear = 0
    @Volatile var outTemp: Int? = null
    @Volatile var fuel = -1f
    @Volatile var status = "Выберите адаптер"

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tM = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val tMI = Typeface.create("sans-serif-medium", Typeface.ITALIC)
    private val tB = Typeface.create("sans-serif", Typeface.BOLD)
    private val tBI = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
    private val tRI = Typeface.create("sans-serif", Typeface.ITALIC)

    private val cDim = Color.parseColor("#7FA2BD")
    private val cLight = Color.parseColor("#D7ECFF")
    private val cWhite = Color.parseColor("#F2FBFF")
    private val cCy = Color.parseColor("#27B6FF")
    private val cOr = Color.parseColor("#FF8A2A")
    private val cHot = Color.parseColor("#FFB066")
    private val cLabel = Color.parseColor("#4FC3F7")
    private val cLabel2 = Color.parseColor("#8FD3FF")
    private val cNeedle = Color.parseColor("#BFEAFF")

    private val spd = Dial(342f, 398f, 220f, 20f, 10f, 5f, 20f, .82f,
        listOf(0f to "#1C7BFF", .35f to "#27B6FF", .6f to "#8FE8FF", .72f to "#DFF6FF", .82f to "#FFC44A", 1f to "#FF8A1F"))
    private val rpmD = Dial(1612f, 398f, 8f, 1f, .5f, .25f, 1f, .75f,
        listOf(0f to "#1C7BFF", .3f to "#27B6FF", .55f to "#9BEBFF", .7f to "#F4FBFF", .76f to "#FFB13B", 1f to "#FF3B2A"))

    private val fuelCols = intArrayOf("#FFB23E", "#27A9FF", "#35B8FF", "#4CC9FF", "#7FDCFF", "#A8E8FF").map { Color.parseColor(it) }.toIntArray()
    private val coolCols = intArrayOf("#1EA0FF", "#27B6FF", "#4CC9FF", "#7FDCFF", "#FF8A2A", "#FF3B2A").map { Color.parseColor(it) }.toIntArray()
    private val unlit = Color.argb(72, 70, 110, 150)

    private val car: Bitmap? by lazy {
        try {
            val src = BitmapFactory.decodeResource(resources, R.drawable.car, BitmapFactory.Options().apply { inScaled = false })
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val cc = Canvas(out); cc.drawBitmap(src, 0f, 0f, null)
            val mp = Paint(); mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            mp.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, .68f, 1f), Shader.TileMode.CLAMP)
            cc.save(); cc.translate(src.width / 2f, src.height / 2f); cc.scale(src.width / 2f, src.height / 2f)
            cc.drawRect(-1f, -1f, 1f, 1f, mp); cc.restore()
            out
        } catch (e: Exception) { null }
    }

    private var staticBmp: Bitmap? = null
    private var sc = 1f; private var ox = 0f; private var oy = 0f
    private var t0 = 0L
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val path = Path()

    private fun pt(color: Int, w: Float = 0f): Paint {
        p.reset(); p.isAntiAlias = true; p.color = color
        if (w > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND }
        return p
    }
    private fun tx(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface = tM, al: Paint.Align = Paint.Align.LEFT) {
        p.reset(); p.isAntiAlias = true; p.color = color; p.textSize = size; p.typeface = tf; p.textAlign = al
        c.drawText(s, x, y, p)
    }
    private fun tw(s: String, size: Float, tf: Typeface): Float {
        p.reset(); p.textSize = size; p.typeface = tf; return p.measureText(s)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        sc = min(w / DW, h / DH); ox = (w - DW * sc) / 2f; oy = (h - DH * sc) / 2f
        val bmp = Bitmap.createBitmap((DW * sc).toInt().coerceAtLeast(1), (DH * sc).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.scale(sc, sc)
        drawStatic(c); staticBmp = bmp
    }

    /* ---------- статическая часть ---------- */
    private fun dialStatic(c: Canvas, d: Dial) {
        c.drawArc(d.rectOuter, 135f - 2.3f, 255f + 4.6f, false, pt(Color.argb(56, 39, 182, 255), 2f))
        val tr = pt(Color.WHITE, 7f); tr.shader = d.shader; tr.alpha = 102
        c.drawArc(d.rect, 135f, 255f, false, tr)

        val n = (d.max / d.sub).roundToInt()
        fun isK(v: Float, q: Float) = abs(v / q - (v / q).roundToInt()) < 1e-4
        for (i in 0..n) {
            val v = i * d.sub; val a = d.ang(v); val co = cos(a); val si = sin(a); val ro = 222f
            val len: Float; val w: Float; var col: Int
            if (isK(v, d.major)) { len = 18f; w = 2.6f; col = cLight }
            else if (isK(v, d.minor)) { len = 11f; w = 2f; col = Color.parseColor("#6AA7D6") }
            else { len = 6f; w = 1.4f; col = Color.parseColor("#3D6A91") }
            if (v / d.max >= d.tail) col = Color.parseColor("#FFA23A")
            c.drawLine(d.cx + co * ro, d.cy + si * ro, d.cx + co * (ro - len), d.cy + si * (ro - len), pt(col, w))
        }
        var v = 0f
        while (v <= d.max + 1e-4f) {
            val a = d.ang(v)
            tx(c, v.roundToInt().toString(), d.cx + cos(a) * 170f, d.cy + sin(a) * 170f + 9f, 26f, cLight, tMI, Paint.Align.CENTER)
            v += d.label
        }
        c.drawCircle(d.cx, d.cy, 112f, pt(Color.argb(235, 8, 16, 28)).also { it.style = Paint.Style.FILL })
        c.drawCircle(d.cx, d.cy, 112f, pt(Color.argb(77, 60, 130, 190), 2f))
    }

    private fun icoTherm(c: Canvas, cx: Float, cy: Float, col: Int, big: Boolean) {
        val q = pt(col, 3f)
        c.drawLine(cx, cy - 22, cx, cy + 6, q); c.drawCircle(cx, cy + 13, 7f, pt(col, 3f))
        for (d in floatArrayOf(-14f, -6f)) c.drawLine(cx + 5, cy + d, cx + 12, cy + d, pt(col, 3f))
        for (d in floatArrayOf(26f, 34f)) {
            path.reset(); path.moveTo(cx - 24, cy + d); path.quadTo(cx - 12, cy + d - 7, cx, cy + d); path.quadTo(cx + 12, cy + d + 7, cx + 24, cy + d)
            c.drawPath(path, pt(col, 3f))
        }
    }
    private fun icoGauge(c: Canvas, cx: Float, cy: Float) {
        c.drawCircle(cx, cy, 23f, pt(cLabel, 3f))
        c.drawLine(cx, cy + 4, cx + 11, cy - 10, pt(cLabel, 3f))
        c.drawCircle(cx, cy, 3f, pt(cLabel, 3f))
        c.drawArc(RectF(cx - 15, cy - 15, cx + 15, cy + 15), 144f, 252f, false, pt(cLabel, 2f))
    }
    private fun icoFuel(c: Canvas, cx: Float, cy: Float, col: Int) {
        c.drawRoundRect(cx - 14, cy - 20, cx + 6, cy + 20, 3f, 3f, pt(col, 3f))
        c.drawRoundRect(cx - 9, cy - 14, cx + 1, cy - 3, 1f, 1f, pt(col, 3f))
        path.reset(); path.moveTo(cx + 6, cy - 6); path.lineTo(cx + 16, cy - 6); path.lineTo(cx + 16, cy + 10)
        c.drawPath(path, pt(col, 3f))
    }

    private fun drawStatic(c: Canvas) {
        val bg = pt(Color.WHITE).also {
            it.shader = RadialGradient(DW / 2, DH * .45f, DW * .62f, Color.parseColor("#0D1B2A"), Color.parseColor("#04070C"), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(0f, 0f, DW, DH, 34f, 34f, bg)
        dialStatic(c, spd); dialStatic(c, rpmD)
        car?.let { c.drawBitmap(it, 560f, 215f, null) }

        // верхняя плашка
        val pl = pt(Color.argb(140, 39, 182, 255), 2f); pl.setShadowLayer(10f, 0f, 0f, cCy)
        path.reset(); path.moveTo(527f, 128f); path.lineTo(527f, 150f); path.quadTo(540f, 184f, 575f, 184f)
        path.lineTo(1340f, 184f); path.quadTo(1378f, 184f, 1390f, 150f); path.lineTo(1390f, 128f)
        c.drawPath(path, pl)
        path.reset(); path.moveTo(722f, 180f); path.lineTo(742f, 158f); path.lineTo(818f, 158f); c.drawPath(path, pl)
        path.reset(); path.moveTo(1100f, 158f); path.lineTo(1170f, 158f); path.lineTo(1195f, 180f); c.drawPath(path, pl)

        // правая панель
        c.drawRoundRect(1170f, 242f, 1422f, 533f, 18f, 18f, pt(Color.argb(199, 10, 22, 36)).also { it.style = Paint.Style.FILL })
        c.drawRoundRect(1170f, 242f, 1422f, 533f, 18f, 18f, pt(Color.argb(115, 39, 182, 255), 2f))
        for (y in floatArrayOf(335f, 434f)) c.drawLine(1182f, y, 1410f, y, pt(Color.argb(64, 39, 182, 255), 1.5f))
        icoTherm(c, 1212f, 282f, cLabel, false); icoGauge(c, 1212f, 384f); icoGauge(c, 1212f, 484f)

        // нижняя панель
        c.drawRoundRect(70f, 582f, 1850f, 680f, 44f, 44f, pt(Color.argb(235, 8, 16, 28)).also { it.style = Paint.Style.FILL })
        val tl = pt(Color.WHITE, 2f)
        tl.shader = LinearGradient(70f, 0f, 1850f, 0f, intArrayOf(Color.argb(0, 39, 182, 255), Color.argb(204, 39, 182, 255), Color.argb(0, 39, 182, 255)), null, Shader.TileMode.CLAMP)
        c.drawLine(110f, 583f, 1810f, 583f, tl)
        icoFuel(c, 127f, 617f, Color.parseColor("#7FD0FF"))
        icoTherm(c, 1789f, 612f, Color.parseColor("#7FD0FF"), true)
        tx(c, "E", 178f, 634f, 22f, Color.parseColor("#FFB23E")); tx(c, "F", 433f, 634f, 22f, cLight)
        tx(c, "C", 1466f, 634f, 22f, cLight); tx(c, "H", 1725f, 634f, 22f, Color.parseColor("#FF5A3A"))
        tx(c, "ПУТЬ", 549f, 640f, 18f, cDim)
    }

    /* ---------- динамическая часть ---------- */
    private fun dialDyn(c: Canvas, d: Dial, f: Float) {
        if (f > 0.003f) {
            val q = pt(Color.WHITE, 7f); q.shader = d.shader
            q.setShadowLayer(22f, 0f, 0f, if (f > d.tail) cOr else cCy)
            c.drawArc(d.rect, 135f, 255f * f, false, q)
        }
        val a = Math.toRadians(135.0 + 255.0 * f).toFloat()
        val n = pt(cNeedle, 5f); n.setShadowLayer(14f, 0f, 0f, cCy)
        c.drawLine(d.cx + cos(a) * 122f, d.cy + sin(a) * 122f, d.cx + cos(a) * 198f, d.cy + sin(a) * 198f, n)
    }

    private fun segs(c: Canvas, x0: Float, y0: Float, lit: Int, cols: IntArray) {
        for (i in 0 until 6) {
            val x = x0 + i * 37f
            val q = pt(if (i < lit) cols[i] else unlit).also { it.style = Paint.Style.FILL }
            if (i < lit) q.setShadowLayer(10f, 0f, 0f, cols[i])
            path.reset(); path.moveTo(x + 8, y0); path.lineTo(x + 38, y0); path.lineTo(x + 30, y0 + 14); path.lineTo(x, y0 + 14); path.close()
            c.drawPath(path, q)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        staticBmp?.let { c.drawBitmap(it, ox, oy, null) } ?: return
        if (t0 == 0L) t0 = System.nanoTime()
        val t = (System.nanoTime() - t0) / 1e9f
        val booting = t < 2.8f
        c.save(); c.translate(ox, oy); c.scale(sc, sc)

        val fs: Float; val fr: Float
        if (booting) {
            val pr = t / 2.8f
            fun e(x: Float) = if (x < .5f) 4 * x * x * x else 1 - Math.pow((-2 * x + 2).toDouble(), 3.0).toFloat() / 2
            val v = if (pr < .5f) e(pr * 2) else 1 - e(pr * 2 - 1)
            fs = v; fr = v
        } else {
            fs = min(speed.toFloat(), spd.max) / spd.max
            fr = min(rpm.toFloat(), 8000f) / 8000f
        }
        dialDyn(c, spd, fs); dialDyn(c, rpmD, fr)

        val sp = speed; val hot = sp > 110
        tx(c, "КМ/Ч", spd.cx, 342f, 15f, cDim, tM, Paint.Align.CENTER)
        tx(c, sp.toString(), spd.cx, 432f, 96f, if (hot) cHot else cWhite, tBI, Paint.Align.CENTER)
        tx(c, "КМ/Ч", spd.cx, 466f, 20f, cLight, tM, Paint.Align.CENTER)
        tx(c, rpm.toString(), rpmD.cx, 410f, 66f, if (rpm > 6000) cHot else cWhite, tBI, Paint.Align.CENTER)
        tx(c, "об/мин", rpmD.cx, 442f, 20f, cLight, tM, Paint.Align.CENTER)

        tx(c, timeFmt.format(Date()), 635f, 168f, 30f, Color.parseColor("#E8F6FF"), tM, Paint.Align.CENTER)
        tx(c, "ВАЗ 2105", 958f, 161f, 32f, Color.WHITE, tB, Paint.Align.CENTER)
        val ot = outTemp
        tx(c, if (ot == null) "--°C" else (if (ot >= 0) "+$ot°C" else "$ot°C"), 1283f, 167f, 28f, Color.parseColor("#E8F6FF"), tM, Paint.Align.CENTER)

        // правая панель
        data class Row(val label: String, val value: String, val unit: String, val ly: Float, val vy: Float, val col: Int)
        val rows = listOf(
            Row("Температура ОЖ", temp.toString(), "°C", 271f, 312f, cLabel),
            Row("Максимум", maxSpeed.toString(), "км/ч", 367f, 412f, cLabel2),
            Row("Средняя", avg.roundToInt().toString(), "км/ч", 467f, 512f, cLabel2)
        )
        rows.forEach { r ->
            tx(c, r.label, 1264f, r.ly, 17f, r.col)
            tx(c, r.value, 1264f, r.vy, 36f, cWhite, tMI)
            tx(c, r.unit, 1264f + tw(r.value, 36f, tMI) + 8f, r.vy, 19f, cLight, tRI)
        }

        // низ
        val lf = if (fuel < 0) 0 else (fuel * 6).roundToInt()
        val tf = ((temp - 40) / 70f).coerceIn(0f, 1f)
        segs(c, 205f, 619f, lf, fuelCols)
        segs(c, 1498f, 619f, (tf * 6).roundToInt(), coolCols)
        val u1 = pt(Color.WHITE, 2f)
        u1.shader = LinearGradient(205f, 0f, 420f, 0f, intArrayOf(Color.argb(204, 255, 178, 62), Color.argb(204, 127, 220, 255), Color.argb(26, 127, 220, 255)), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
        c.drawLine(205f, 642f, 420f, 642f, u1)
        val u2 = pt(Color.WHITE, 2f)
        u2.shader = LinearGradient(1498f, 0f, 1712f, 0f, intArrayOf(Color.argb(26, 127, 220, 255), Color.argb(204, 127, 220, 255), Color.argb(230, 255, 90, 58)), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        c.drawLine(1498f, 642f, 1712f, 642f, u2)

        val tripS = String.format(Locale.US, "%06.1f", trip)
        tx(c, tripS, 617f, 642f, 32f, cWhite, tMI)
        tx(c, "км", 617f + tw(tripS, 32f, tMI) + 10f, 642f, 18f, cLight, tRI)

        val gs = arrayOf("N", "1", "2", "3", "4", "5")
        gs.forEachIndexed { i, s ->
            val x = 868f + i * 50f; val on = gear == i
            if (on) {
                c.drawRoundRect(x - 24, 608f, x + 24, 654f, 8f, 8f, pt(Color.argb(36, 39, 182, 255)).also { it.style = Paint.Style.FILL })
                c.drawRoundRect(x - 24, 608f, x + 24, 654f, 8f, 8f, pt(cCy, 3f).also { it.setShadowLayer(12f, 0f, 0f, cCy) })
            }
            tx(c, s, x, 641f, 28f, if (on) Color.WHITE else cDim, if (on) tB else tM, Paint.Align.CENTER)
        }

        if (status.isNotEmpty()) tx(c, status, 958f, 236f, 22f, Color.parseColor("#FFD166"), tM, Paint.Align.CENTER)
        c.restore()
        if (booting) postInvalidateOnAnimation()
    }
}
