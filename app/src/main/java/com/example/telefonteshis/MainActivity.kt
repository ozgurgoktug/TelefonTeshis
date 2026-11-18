package com.example.telefonteshis

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var verdict: TextView
    private lateinit var info: TextView
    private lateinit var speedResult: TextView
    private lateinit var ramBar: ProgressBar
    private lateinit var romBar: ProgressBar
    private lateinit var cpuBar: ProgressBar
    private lateinit var ramText: TextView
    private lateinit var romText: TextView
    private lateinit var cpuText: TextView

    private var prevTotal = 0L
    private var prevIdle = 0L

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    // ---------------- UI ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(size: Float = 14f, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        layoutParams = LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(4) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#121212"))
        }
        root.addView(label(22f, bold = true).apply { text = "Telefon Teşhis" })
        verdict = label(15f, bold = true).apply { setPadding(0, dp(8), 0, dp(12)) }
        root.addView(verdict)

        fun section(title: String): Pair<ProgressBar, TextView> {
            root.addView(label(13f, bold = true).apply {
                text = title
                setPadding(0, dp(10), 0, 0)
                setTextColor(Color.parseColor("#90CAF9"))
            })
            val b = bar()
            val t = label(13f)
            root.addView(b)
            root.addView(t)
            return b to t
        }
        section("RAM").let { ramBar = it.first; ramText = it.second }
        section("Depolama (ROM)").let { romBar = it.first; romText = it.second }
        section("CPU").let { cpuBar = it.first; cpuText = it.second }

        info = label(12.5f, mono = true).apply { setPadding(0, dp(14), 0, dp(10)) }
        root.addView(info)

        val btn = Button(this).apply {
            text = "Depolama hız testi (64 MB)"
            setOnClickListener { isEnabled = false; speedTest { isEnabled = true } }
        }
        root.addView(btn, LinearLayout.LayoutParams(-1, -2))
        speedResult = label(13f, mono = true)
        root.addView(speedResult)

        setContentView(ScrollView(this).apply {
            addView(root)
            setBackgroundColor(Color.parseColor("#121212"))
        })
    }

    // ---------------- Veri toplama ----------------

    private fun readFile(path: String): String? = try { File(path).readText().trim() } catch (e: Exception) { null }

    private fun mb(b: Long) = "%.0f MB".format(b / 1048576.0)
    private fun gb(b: Long) = "%.1f GB".format(b / 1073741824.0)

    private fun meminfo(): Map<String, Long> {
        val m = HashMap<String, Long>()
        readFile("/proc/meminfo")?.lines()?.forEach { l ->
            val p = l.split(Regex("\\s+"))
            if (p.size >= 2) p[1].toLongOrNull()?.let { m[p[0].trimEnd(':')] = it * 1024 }
        }
        return m
    }

    /** /proc/stat Android 8+ üzerinde çoğu cihazda kapalı; kapalıysa null döner. */
    private fun cpuUsageFromProc(): Int? {
        val line = readFile("/proc/stat")?.lines()?.firstOrNull { it.startsWith("cpu ") } ?: return null
        val v = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 5) return null
        val idle = v[3] + v[4]
        val total = v.sum()
        val dT = total - prevTotal
        val dI = idle - prevIdle
        val first = prevTotal == 0L
        prevTotal = total
        prevIdle = idle
        if (first || dT <= 0) return null
        return (100 * (dT - dI) / dT).toInt()
    }

    private data class Core(val idx: Int, val cur: Long, val max: Long, val online: Boolean)

    private fun cores(): List<Core> {
        val n = Runtime.getRuntime().availableProcessors()
        return (0 until n).map { i ->
            val base = "/sys/devices/system/cpu/cpu$i"
            val online = readFile("$base/online")?.let { it == "1" } ?: true
            val cur = readFile("$base/cpufreq/scaling_cur_freq")?.toLongOrNull() ?: 0L
            val max = readFile("$base/cpufreq/cpuinfo_max_freq")?.toLongOrNull() ?: 0L
            Core(i, cur, max, online)
        }
    }

    private fun thermalZones(): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        val dirs = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return out
        for (d in dirs.sortedBy { it.name }) {
            val raw = readFile("${d.path}/temp")?.toDoubleOrNull() ?: continue
            val t = if (raw > 1000) raw / 1000.0 else raw
            if (t in 1.0..150.0) out += (readFile("${d.path}/type") ?: d.name) to t
        }
        return out
    }

    private fun refresh() {
        val warnings = ArrayList<String>()

        // RAM
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val usedRam = mi.totalMem - mi.availMem
        val ramPct = (100 * usedRam / mi.totalMem).toInt()
        val mem = meminfo()
        val swapTotal = mem["SwapTotal"] ?: 0L
        val swapUsed = swapTotal - (mem["SwapFree"] ?: 0L)
        ramBar.progress = ramPct
        ramText.text = "Kullanılan ${mb(usedRam)} / ${mb(mi.totalMem)}  (%$ramPct)\n" +
            "Boş (kullanılabilir): ${mb(mi.availMem)}   Önbellek: ${mb(mem["Cached"] ?: 0)}\n" +
            "Swap/zRAM: ${mb(swapUsed)} / ${mb(swapTotal)}" +
            (if (mi.lowMemory) "\n⚠ SİSTEM DÜŞÜK BELLEK MODUNDA" else "") +
            "\n\nDağılım:\n" +
            "  Uygulama+servis verisi: ${mb(mem["AnonPages"] ?: 0)}\n" +
            "  Çekirdek (Slab+Stack+PageTbl): ${mb((mem["Slab"] ?: 0) + (mem["KernelStack"] ?: 0) + (mem["PageTables"] ?: 0))}\n" +
            "  Paylaşılan (Shmem/GPU/grafik): ${mb(mem["Shmem"] ?: 0)}\n" +
            "  Önbellek (gerekirse silinir): ${mb((mem["Cached"] ?: 0) + (mem["Buffers"] ?: 0))}\n" +
            "  Tamamen boş: ${mb(mem["MemFree"] ?: 0)}\n" +
            "Hangi uygulama? Android bunu uygulamalara göstermez.\n" +
            "PC'den: adb shell dumpsys meminfo   (en üstte uygulama başına RAM)"
        if (mi.lowMemory) warnings += "Sistem düşük bellek durumunda: arka plandaki uygulamalar sürekli kapatılıyor."
        else if (ramPct > 88) warnings += "RAM %$ramPct dolu. Arka plan uygulamaları RAM'i tüketiyor olabilir."
        if (swapTotal > 0 && swapUsed * 100 / swapTotal > 70) warnings += "Swap yoğun kullanılıyor, bu telefonu belirgin yavaşlatır."

        // ROM
        val st = StatFs(Environment.getDataDirectory().path)
        val romTotal = st.totalBytes
        val romFree = st.availableBytes
        val romPct = (100 * (romTotal - romFree) / romTotal).toInt()
        romBar.progress = romPct
        romText.text = "Kullanılan ${gb(romTotal - romFree)} / ${gb(romTotal)}  (%$romPct)\nBoş: ${gb(romFree)}"
        if (romFree * 100 / romTotal < 10) warnings += "Depolama %90'dan fazla dolu; flash belleğin hızı düşer, telefon yavaşlar."

        // CPU
        val cs = cores()
        val procPct = cpuUsageFromProc()
        val active = cs.filter { it.online && it.max > 0 && it.cur > 0 }
        val freqPct = if (active.isNotEmpty()) active.map { (100 * it.cur / it.max).toInt() }.average().toInt() else 0
        val pct = procPct ?: freqPct
        cpuBar.progress = pct
        val src = if (procPct != null) "" else "  (tahmin: frekans/maks. frekans; /proc/stat kapalı)"
        cpuText.text = "Kullanım: %$pct$src\n" + cs.joinToString("\n") {
            "Çekirdek ${it.idx}: " + when {
                !it.online -> "kapalı"
                it.cur > 0 -> "${it.cur / 1000} MHz / ${it.max / 1000} MHz"
                else -> "okunamadı"
            }
        }
        val lowFreq = active.isNotEmpty() && active.all { it.cur * 100 / it.max < 40 }

        // Pil / sıcaklık
        val b: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battTemp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val health = when (b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "iyi"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "AŞIRI ISINMA"
            BatteryManager.BATTERY_HEALTH_DEAD -> "ölü"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "aşırı voltaj"
            else -> "bilinmiyor"
        }
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val currentMa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
        val volt = b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val thermalLevel = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        val thermal = when {
            thermalLevel < 0 -> "bu Android sürümünde yok"
            thermalLevel == PowerManager.THERMAL_STATUS_NONE -> "yok (normal)"
            thermalLevel == PowerManager.THERMAL_STATUS_LIGHT -> "hafif"
            thermalLevel == PowerManager.THERMAL_STATUS_MODERATE -> "ORTA (kısıtlama başlamış olabilir)"
            thermalLevel == PowerManager.THERMAL_STATUS_SEVERE -> "AĞIR (performans kısılıyor)"
            else -> "KRİTİK"
        }
        if (thermalLevel >= PowerManager.THERMAL_STATUS_MODERATE)
            warnings += "Termal kısıtlama aktif: telefon ısındığı için işlemci yavaşlatılıyor."
        if (battTemp >= 42) warnings += "Pil sıcaklığı %.1f°C: yüksek, performans düşebilir.".format(battTemp)
        if (lowFreq && pct > 0 && battTemp >= 38) warnings += "CPU frekansları düşük ve cihaz sıcak: büyük ihtimalle termal kısıtlama."

        val zones = thermalZones()
        val sb = StringBuilder()
        sb.append("── SICAKLIK ──\n")
        sb.append("Pil: %.1f°C  (sağlık: %s)\n".format(battTemp, health))
        sb.append("Termal durum: $thermal\n")
        if (zones.isEmpty()) sb.append("thermal_zone: okunamadı (Android erişimi kısıtlıyor)\n")
        else zones.forEach { sb.append("%-18s %.1f°C\n".format(it.first.take(18), it.second)) }
        sb.append("\n── PİL ──\n")
        sb.append("Seviye: %%%d %s\n".format(if (level >= 0) 100 * level / scale else -1, if (charging) "(şarjda)" else ""))
        sb.append("Akım: $currentMa mA   Voltaj: $volt mV\n")
        sb.append("\n── CİHAZ ──\n")
        sb.append("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Güvenlik yaması: ${Build.VERSION.SECURITY_PATCH}\n")
        val up = SystemClock.elapsedRealtime()
        sb.append("Açık kalma: %d sa %d dk\n".format(up / 3600000, up / 60000 % 60))
        info.text = sb.toString()

        if (warnings.isEmpty()) {
            verdict.setTextColor(Color.parseColor("#81C784"))
            verdict.text = "✔ Anlık değerlerde belirgin sorun yok.\nYavaşlık sürekliyse depolama hız testini çalıştırın."
        } else {
            verdict.setTextColor(Color.parseColor("#FFB74D"))
            verdict.text = warnings.joinToString("\n") { "⚠ $it" }
        }
    }

    // ---------------- Depolama hız testi ----------------

    private fun speedTest(done: () -> Unit) {
        speedResult.text = "Test çalışıyor..."
        thread {
            val msg = try {
                val f = File(cacheDir, "speedtest.bin")
                val chunk = ByteArray(1 shl 20) { (it * 31).toByte() }
                val n = 64
                val w0 = System.nanoTime()
                f.outputStream().use { o -> repeat(n) { o.write(chunk) }; o.fd.sync() }
                val w = n / ((System.nanoTime() - w0) / 1e9)
                val r0 = System.nanoTime()
                f.inputStream().use { i -> val buf = ByteArray(1 shl 20); while (i.read(buf) > 0) { } }
                val r = n / ((System.nanoTime() - r0) / 1e9)
                f.delete()
                val warn = if (w < 30) "\n⚠ Yazma hızı çok düşük: depolama (flash) yıpranmış veya dolu olabilir." else ""
                "Yazma: %.0f MB/s\nOkuma: %.0f MB/s (önbellekten olabilir)%s".format(w, r, warn)
            } catch (e: Exception) {
                "Test başarısız: ${e.message}"
            }
            runOnUiThread { speedResult.text = msg; done() }
        }
    }
}
