import ai.nhg.capturepoc.Mp3Encoder
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: Mp3EncoderHarness <fixture.wav> <out.mp3> <invalid-out.mp3>")
        exitProcess(2)
    }
    val wav = File(args[0])
    val out = File(args[1])
    val invalidOut = File(args[2])
    out.delete()
    invalidOut.delete()

    println("available=${Mp3Encoder.isAvailable()}")
    println("lameVersion=${Mp3Encoder.version()}")
    if (!Mp3Encoder.isAvailable()) {
        System.err.println("unavailableReason=${Mp3Encoder.unavailableReason()}")
        exitProcess(3)
    }

    var lastProgress = -1f
    val ok = Mp3Encoder.encodeWavToMp3(wav, out) { p ->
        if (p == 1f || p - lastProgress >= 0.2f) lastProgress = p
    }
    println("encodeOk=$ok size=${out.length()} lastError=${Mp3Encoder.lastError}")
    if (!ok) exitProcess(4)

    val junk = File(wav.parentFile, "mp3-harness-invalid.bin")
    junk.writeBytes(ByteArray(128) { (it * 7).toByte() })
    val bad = Mp3Encoder.encodeWavToMp3(junk, invalidOut)
    println("invalidInputOk=$bad invalidOutputExists=${invalidOut.exists()} lastError=${Mp3Encoder.lastError}")
    junk.delete()
    if (bad || invalidOut.exists()) exitProcess(5)

    println("HARNESS_OK")
}
