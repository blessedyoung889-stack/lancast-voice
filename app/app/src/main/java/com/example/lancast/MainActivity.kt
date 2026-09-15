package com.example.lancast

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.*
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Process
import android.widget.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var groupEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var status: TextView
    private var engine: LanAudioEngine? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        groupEdit = findViewById(R.id.group); portEdit = findViewById(R.id.port); status = findViewById(R.id.status)
        findViewById<Button>(R.id.start).setOnClickListener { startAudio() }
        findViewById<Button>(R.id.stop).setOnClickListener { stopAudio() }
    }

    private fun startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        val group = groupEdit.text.toString().trim()
        val port = portEdit.text.toString().toIntOrNull() ?: 5000
        try {
            val wm = getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("lancast").apply { setReferenceCounted(true); acquire() }
            engine = LanAudioEngine(group, port) { s -> runOnUiThread { status.text = s } }
            engine!!.start()
            findViewById<Button>(R.id.start).isEnabled = false
            findViewById<Button>(R.id.stop).isEnabled = true
        } catch (e: Exception) { status.text = "Error: ${e.message}"; stopAudio() }
    }

    private fun stopAudio() {
        engine?.stop(); engine = null
        multicastLock?.release(); multicastLock = null
        findViewById<Button>(R.id.start).isEnabled = true
        findViewById<Button>(R.id.stop).isEnabled = false
        status.text = "Stopped"
    }

    override fun onDestroy() { stopAudio(); super.onDestroy() }
}

private class LanAudioEngine(private val group: String, private val port: Int, private val status: (String) -> Unit) {
    @Volatile private var running = false
    private var sendSocket: MulticastSocket? = null
    private var recvSocket: MulticastSocket? = null
    private var senderThread: Thread? = null
    private var receiverThread: Thread? = null
    private val ssrc = Random.nextInt()
    private var sequence = 0
    private var timestamp = 0

    fun start() {
        running = true
        senderThread = thread(name = "audio-sender") { senderLoop() }
        receiverThread = thread(name = "audio-receiver") { receiverLoop() }
        status("Running • 48 kHz mono • Opus 24 kbps • RTP/UDP multicast")
    }

    fun stop() { running = false; try { sendSocket?.close(); recvSocket?.close() } catch (_: Exception) {}; senderThread?.interrupt(); receiverThread?.interrupt() }

    private fun senderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val sampleRate = 48000; val channels = 1; val frameSamples = 960
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frameSamples * 2 * 4))
        val codec = MediaCodec.createEncoderByType("audio/opus")
        codec.configure(MediaFormat.createAudioFormat("audio/opus", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSamples * 2); setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        sendSocket = MulticastSocket()
        val address = InetAddress.getByName(group)
        val audio = ShortArray(frameSamples); val pcm = ByteArray(frameSamples * 2); val info = MediaCodec.BufferInfo()
        try {
            record.startRecording()
            while (running) {
                var got = 0; while (got < frameSamples && running) { val n = record.read(audio, got, frameSamples - got, AudioRecord.READ_BLOCKING); if (n > 0) got += n }
                if (!running) break
                val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN); for (s in audio) bb.putShort(s)
                var inIndex = codec.dequeueInputBuffer(5000)
                if (inIndex >= 0) { val ib = codec.getInputBuffer(inIndex)!!; ib.clear(); ib.put(pcm); codec.queueInputBuffer(inIndex, 0, pcm.size, timestamp.toLong(), 0) }
                timestamp += frameSamples
                var out = codec.dequeueOutputBuffer(info, 0)
                while (out >= 0) {
                    val ob = codec.getOutputBuffer(out)!!; val data = ByteArray(info.size); ob.position(info.offset); ob.get(data)
                    val packet = ByteArray(12 + data.size)
                    packet[0] = 0x80.toByte(); packet[1] = 111.toByte() // RTP Opus payload type
                    packet[2] = (sequence ushr 8).toByte(); packet[3] = sequence.toByte();
                    packet[4] = (timestamp ushr 24).toByte(); packet[5] = (timestamp ushr 16).toByte(); packet[6] = (timestamp ushr 8).toByte(); packet[7] = timestamp.toByte()
                    packet[8] = (ssrc ushr 24).toByte(); packet[9] = (ssrc ushr 16).toByte(); packet[10] = (ssrc ushr 8).toByte(); packet[11] = ssrc.toByte()
                    System.arraycopy(data, 0, packet, 12, data.size)
                    sendSocket!!.send(DatagramPacket(packet, packet.size, address, port)); sequence = (sequence + 1) and 0xffff
                    codec.releaseOutputBuffer(out, false); out = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) { if (running) status("Sender error: ${e.message}") }
        try { record.stop() } catch (_: Exception) {}; record.release(); codec.stop(); codec.release(); sendSocket?.close()
    }

    private fun receiverLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val sampleRate = 48000; val channels = 1
        val codec = try { MediaCodec.createDecoderByType("audio/opus") } catch (e: Exception) { status("Opus decoder unavailable: ${e.message}"); return }
        codec.configure(MediaFormat.createAudioFormat("audio/opus", sampleRate, channels), null, null, 0); codec.start()
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(48000).build()
        recvSocket = MulticastSocket(port)
        val addr = InetAddress.getByName(group); recvSocket!!.joinGroup(addr)
        val buf = ByteArray(1500); val info = MediaCodec.BufferInfo(); track.play()
        var lastSeq = -1
        try {
            while (running) {
                val dp = DatagramPacket(buf, buf.size); recvSocket!!.receive(dp); if (dp.length < 13) continue
                val seq = ((buf[2].toInt() and 255) shl 8) or (buf[3].toInt() and 255)
                if (lastSeq >= 0 && ((seq - lastSeq) and 0xffff) > 1000) { lastSeq = seq; continue }
                lastSeq = seq
                val ts = ((buf[4].toInt() and 255) shl 24) or ((buf[5].toInt() and 255) shl 16) or ((buf[6].toInt() and 255) shl 8) or (buf[7].toInt() and 255)
                val payloadLen = dp.length - 12; val inIndex = codec.dequeueInputBuffer(5000); if (inIndex >= 0) { val ib = codec.getInputBuffer(inIndex)!!; ib.clear(); ib.put(buf, 12, payloadLen); codec.queueInputBuffer(inIndex, 0, payloadLen, ts.toLong(), 0) }
                var out = codec.dequeueOutputBuffer(info, 0)
                while (out >= 0) { val ob = codec.getOutputBuffer(out)!!; val pcm = ByteArray(info.size); ob.position(info.offset); ob.get(pcm); track.write(pcm, 0, pcm.size); codec.releaseOutputBuffer(out, false); out = codec.dequeueOutputBuffer(info, 0) }
            }
        } catch (e: Exception) { if (running) status("Receiver error: ${e.message}") }
        try { recvSocket?.leaveGroup(addr) } catch (_: Exception) {}; recvSocket?.close(); track.stop(); track.release(); codec.stop(); codec.release()
    }
}
