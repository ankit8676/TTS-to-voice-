package com.example.srtvoice

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import java.io.*
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var cues: List<Cue> = emptyList()
    private var selectedName = ""
    private lateinit var status: TextView
    private lateinit var chooseButton: Button
    private lateinit var exportWavButton: Button
    private lateinit var exportMp3Button: Button
    private lateinit var rate: SeekBar
    private lateinit var rateLabel: TextView
    private var working = false

    private val openSrt = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSrt(uri)
    }
    private var pendingFormat = "wav"
    private val createOutput = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        if (uri != null) exportTo(uri, pendingFormat)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
    }

    override fun onInit(statusCode: Int) {
        ttsReady = statusCode == TextToSpeech.SUCCESS
        runOnUiThread {
            if (ttsReady) status.text = "TTS ready. Upload an SRT file to begin."
            else status.text = "TTS failed to initialize. Check Android Settings → Text-to-speech output."
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF8F9FC.toInt())
        }
        val title = TextView(this).apply { text = "SRT Voice Converter"; textSize = 25f; setTextColor(0xFF18213A.toInt()); gravity = Gravity.CENTER_VERTICAL }
        root.addView(title, matchWrap())
        val subtitle = TextView(this).apply { text = "Local Android TTS • timestamp-aware export"; textSize = 14f; setTextColor(0xFF596579.toInt()); setPadding(0, 6, 0, 22) }
        root.addView(subtitle, matchWrap())
        chooseButton = Button(this).apply { text = "Choose .SRT file"; setOnClickListener { if (!working) openSrt.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) } }
        root.addView(chooseButton, matchWrap())
        rateLabel = TextView(this).apply { text = "Base speech rate: 1.0×"; textSize = 15f; setPadding(0, 18, 0, 0) }
        root.addView(rateLabel, matchWrap())
        rate = SeekBar(this).apply { max = 15; progress = 5; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { rateLabel.text = "Base speech rate: ${String.format(Locale.US, "%.1f", 0.5 + progress * 0.1)}×" }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }) }
        root.addView(rate, matchWrap())
        exportWavButton = Button(this).apply { text = "Generate & save WAV"; isEnabled = false; setOnClickListener { beginExport("wav") } }
        root.addView(exportWavButton, matchWrap())
        exportMp3Button = Button(this).apply { text = "Generate & save MP3"; isEnabled = false; setOnClickListener { beginExport("mp3") } }
        root.addView(exportMp3Button, matchWrap())
        status = TextView(this).apply { text = "Initializing Android Text-to-Speech…"; textSize = 14f; setTextColor(0xFF344054.toInt()); setPadding(0, 18, 0, 0) }
        root.addView(status, matchWrap())
        val note = TextView(this).apply {
            text = "How timing works: each cue starts at its SRT timestamp. If speech is longer than its slot, it is sped up to fit. This may change pitch. Overlapping subtitle cues are mixed together."
            textSize = 12f; setTextColor(0xFF667085.toInt()); setPadding(0, 22, 0, 0)
        }
        root.addView(note, matchWrap())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun loadSrt(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Could not open file")
            cues = SrtParser.parse(text)
            selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "subtitles.srt"
            status.text = "Loaded $selectedName — ${cues.size} subtitle cues."
            exportWavButton.isEnabled = ttsReady
            exportMp3Button.isEnabled = ttsReady
        } catch (e: Exception) {
            cues = emptyList(); exportWavButton.isEnabled = false; exportMp3Button.isEnabled = false
            status.text = "Could not read SRT: ${e.message}"
        }
    }

    private fun beginExport(format: String) {
        if (working || cues.isEmpty() || !ttsReady) return
        pendingFormat = format
        val mime = if (format == "mp3") "audio/mpeg" else "audio/wav"
        createOutput.launch("${selectedName.substringBeforeLast('.', "subtitles")}_voice.$format")
        // The CreateDocument contract MIME is declared at registration time; Android still uses the chosen extension.
    }

    private fun exportTo(destination: Uri, format: String) {
        if (working) return
        working = true; setControls(false)
        status.text = "Generating speech… keep the app open."
        thread(name = "srt-tts-export") {
            try {
                val pcm = synthesizeTimeline(cues)
                val tempWav = File(cacheDir, "srt_voice_${System.currentTimeMillis()}.wav")
                FileOutputStream(tempWav).use { out ->
                    val bytes = pcm.size.toLong() * 2
                    WavTools.writeHeader(out, 22050, 1, bytes)
                    val buffer = ByteArray(8192)
                    var pos = 0
                    while (pos < pcm.size) {
                        val n = minOf(buffer.size / 2, pcm.size - pos)
                        for (i in 0 until n) {
                            val v = pcm[pos + i].toInt()
                            buffer[i * 2] = (v and 255).toByte(); buffer[i * 2 + 1] = ((v ushr 8) and 255).toByte()
                        }
                        out.write(buffer, 0, n * 2); pos += n
                    }
                }
                if (format == "wav") {
                    contentResolver.openOutputStream(destination)?.use { it.write(tempWav.readBytes()) } ?: error("Could not create output file")
                } else {
                    // AME's encoder converts WAV -> MP3 locally. Keep this on a worker thread.
                    val mp3 = File(cacheDir, "srt_voice_${System.currentTimeMillis()}.mp3")
                    val latch = CountDownLatch(1)
                    var encodeError = true
                    try {
                        io.auxo.ame.Mp3Encoder.encode(tempWav, mp3, object : io.auxo.ame.Mp3Encoder.Callback {
                            override fun onStart() {}
                            override fun onProgress(total: Int, current: Int) { runOnUiThread { status.text = "Encoding MP3: $current / $total" } }
                            override fun onComplete() { encodeError = false; latch.countDown() }
                            override fun onError() { encodeError = true; latch.countDown() }
                        })
                    } catch (e: Exception) { throw IllegalStateException("MP3 encoder could not start: ${e.message}") }
                    if (!latch.await(5, TimeUnit.MINUTES) || encodeError || !mp3.exists() || mp3.length() == 0L) error("MP3 encoding failed. Try WAV export or check the encoder dependency.")
                    contentResolver.openOutputStream(destination)?.use { mp3.inputStream().use { input -> input.copyTo(it) } } ?: error("Could not create output file")
                    mp3.delete()
                }
                tempWav.delete()
                runOnUiThread { status.text = "Done! Saved ${cues.size} cues as ${format.uppercase(Locale.US)}."; working = false; setControls(true) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Export failed: ${e.message ?: e.javaClass.simpleName}"; working = false; setControls(true) }
            }
        }
    }

    private fun synthesizeTimeline(subtitles: List<Cue>): ShortArray {
        val sampleRate = 22050
        val endMs = subtitles.maxOf { it.endMs }
        require(endMs <= 60L * 60L * 1000L) { "This MVP limits one export to 60 minutes." }
        val timeline = ShortArray((endMs * sampleRate / 1000L).toInt() + sampleRate)
        val baseRate = 0.5f + rate.progress * 0.1f
        val engine = tts ?: error("TTS is not initialized")
        for ((i, cue) in subtitles.withIndex()) {
            val clean = cue.text.take(TextToSpeech.getMaxSpeechInputLength())
            val file = File(cacheDir, "cue_$i.wav")
            val latch = CountDownLatch(1)
            var failure: String? = null
            engine.setSpeechRate(baseRate)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { latch.countDown() }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { failure = "TTS error for cue ${cue.index}"; latch.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) { failure = "TTS error $errorCode for cue ${cue.index}"; latch.countDown() }
            })
            val result = engine.synthesizeToFile(clean, Bundle(), file, "cue_$i")
            if (result != TextToSpeech.SUCCESS) error("TTS could not queue cue ${cue.index}")
            if (!latch.await(90, TimeUnit.SECONDS)) error("Timed out while synthesizing cue ${cue.index}")
            failure?.let { error(it) }
            val wav = WavTools.read(file)
            file.delete()
            val mono = toMono22050(wav)
            val slot = cue.endMs - cue.startMs
            val fitted = WavTools.fitToSlot(mono, slot)
            val startFrame = (cue.startMs * sampleRate / 1000L).toInt()
            val count = minOf(fitted.samples.size, timeline.size - startFrame)
            // Saturating mix allows overlapping subtitle intervals without integer overflow.
            for (j in 0 until count) timeline[startFrame + j] = (timeline[startFrame + j].toInt() + fitted.samples[j].toInt()).coerceIn(-32768, 32767).toShort()
            runOnUiThread { status.text = "Generating voice: ${i + 1} / ${subtitles.size}" }
        }
        return timeline.copyOf((endMs * sampleRate / 1000L).toInt().coerceAtLeast(1))
    }

    private fun toMono22050(wav: PcmWav): PcmWav {
        val frames = wav.samples.size / wav.channels
        val mono = ShortArray(frames) { frame ->
            if (wav.channels == 1) wav.samples[frame]
            else ((wav.samples[frame * 2].toInt() + wav.samples[frame * 2 + 1].toInt()) / 2).toShort()
        }
        if (wav.sampleRate == 22050) return PcmWav(22050, 1, mono)
        val outFrames = (frames.toDouble() * 22050 / wav.sampleRate).toInt().coerceAtLeast(1)
        val resampled = ShortArray(outFrames) { i ->
            val p = i.toDouble() * wav.sampleRate / 22050
            val a = p.toInt().coerceIn(0, frames - 1); val b = minOf(a + 1, frames - 1); val f = p - a
            (mono[a] + (mono[b] - mono[a]) * f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return PcmWav(22050, 1, resampled)
    }

    private fun setControls(enabled: Boolean) {
        chooseButton.isEnabled = enabled
        rate.isEnabled = enabled
        exportWavButton.isEnabled = enabled && cues.isNotEmpty() && ttsReady
        exportMp3Button.isEnabled = enabled && cues.isNotEmpty() && ttsReady
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }
}
