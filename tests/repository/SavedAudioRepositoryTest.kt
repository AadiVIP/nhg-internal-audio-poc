import ai.nhg.capturepoc.AudioOutputFormat
import ai.nhg.capturepoc.SavedAudioRepository
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

fun main() {
    println("=== Running SavedAudioRepository Regression Tests ===")
    val tempDir = File(System.getProperty("java.io.tmpdir"), "nhg-repo-test-" + System.currentTimeMillis())
    val sessionDir = File(tempDir, "sessions").apply { mkdirs() }
    val exportDir = File(tempDir, "downloads").apply { mkdirs() }

    try {
        // --- TEST 1: MP3 Session (1 master WAV, 1 internal MP3, 1 exported MP3) ---
        println("[Test 1] Simulating MP3 session with master WAV, internal MP3, and exported MP3...")
        val mp3Sid = "20261029-100000-001"
        val mp3Wav = File(sessionDir, "$mp3Sid.wav").apply { writeBytes(ByteArray(1000) { 1 }) }
        val mp3Internal = File(sessionDir, "$mp3Sid.mp3").apply { writeBytes(ByteArray(200) { 2 }) }
        val mp3Exported = File(exportDir, "$mp3Sid.mp3").apply { writeBytes(ByteArray(200) { 2 }) }
        val mp3Meta = JSONObject().apply {
            put("sessionId", mp3Sid)
            put("outputFormat", "MP3")
            put("outputFormatActual", "MP3")
            put("audioFile", mp3Internal.canonicalPath)
            put("wavFile", mp3Wav.canonicalPath)
            put("exportedAudio", mp3Exported.canonicalPath)
            put("durationMs", 5000L)
            put("sha256", "dummy-sha-mp3")
        }
        File(sessionDir, "$mp3Sid.json").writeText(mp3Meta.toString(2))
        File(exportDir, "$mp3Sid.json").writeText(mp3Meta.toString(2))

        val res1 = SavedAudioRepository.loadRecordingsFromDirs(sessionDir, exportDir)
        println("  Found items: ${res1.size} -> ${res1.map { "${it.name} (${it.format})" }}")
        check(res1.size == 1) { "Expected exactly 1 item for MP3 session, got ${res1.size}" }
        check(res1[0].id == mp3Sid) { "Expected ID $mp3Sid, got ${res1[0].id}" }
        check(res1[0].format == AudioOutputFormat.MP3) { "Expected format MP3, got ${res1[0].format}" }
        check(res1[0].name == "$mp3Sid.mp3") { "Expected name $mp3Sid.mp3, got ${res1[0].name}" }
        check(res1[0].file.canonicalPath == mp3Exported.canonicalPath) { "Expected primary file to be exported copy" }
        check(res1[0].internalFile?.canonicalPath == mp3Internal.canonicalPath) { "Expected internalFile reference" }
        check(res1[0].masterWavFile?.canonicalPath == mp3Wav.canonicalPath) { "Expected masterWavFile reference" }
        println("  PASS: MP3 session produced exactly ONE .mp3 item. Master WAV and exported duplicate correctly deduplicated.")

        // --- TEST 2: M4A Session (1 master WAV, 1 internal M4A, 1 exported M4A) ---
        println("[Test 2] Simulating M4A session with master WAV, internal M4A, and exported M4A...")
        val m4aSid = "20261029-200000-002"
        val m4aWav = File(sessionDir, "$m4aSid.wav").apply { writeBytes(ByteArray(1000) { 1 }) }
        val m4aInternal = File(sessionDir, "$m4aSid.m4a").apply { writeBytes(ByteArray(250) { 3 }) }
        val m4aExported = File(exportDir, "$m4aSid.m4a").apply { writeBytes(ByteArray(250) { 3 }) }
        val m4aMeta = JSONObject().apply {
            put("sessionId", m4aSid)
            put("outputFormat", "M4A")
            put("outputFormatActual", "M4A")
            put("audioFile", m4aInternal.canonicalPath)
            put("wavFile", m4aWav.canonicalPath)
            put("exportedAudio", m4aExported.canonicalPath)
            put("durationMs", 6000L)
            put("sha256", "dummy-sha-m4a")
        }
        File(sessionDir, "$m4aSid.json").writeText(m4aMeta.toString(2))
        File(exportDir, "$m4aSid.json").writeText(m4aMeta.toString(2))

        val res2 = SavedAudioRepository.loadRecordingsFromDirs(sessionDir, exportDir)
        println("  Found items: ${res2.size} -> ${res2.map { "${it.name} (${it.format})" }}")
        check(res2.size == 2) { "Expected 2 items total (MP3 + M4A), got ${res2.size}" }
        val m4aItem = res2.first { it.id == m4aSid }
        check(m4aItem.format == AudioOutputFormat.M4A) { "Expected format M4A, got ${m4aItem.format}" }
        check(m4aItem.name == "$m4aSid.m4a") { "Expected name $m4aSid.m4a, got ${m4aItem.name}" }
        check(m4aItem.file.canonicalPath == m4aExported.canonicalPath) { "Expected primary file to be exported copy" }
        check(m4aItem.masterWavFile?.canonicalPath == m4aWav.canonicalPath) { "Expected masterWavFile reference" }
        println("  PASS: M4A session produced exactly ONE .m4a item. Master WAV and exported duplicate correctly deduplicated.")

        // --- TEST 3: WAV Session (1 internal WAV, 1 exported WAV) ---
        println("[Test 3] Simulating WAV session with internal WAV and exported WAV...")
        val wavSid = "20261029-300000-003"
        val wavInternal = File(sessionDir, "$wavSid.wav").apply { writeBytes(ByteArray(1200) { 1 }) }
        val wavExported = File(exportDir, "$wavSid.wav").apply { writeBytes(ByteArray(1200) { 1 }) }
        val wavMeta = JSONObject().apply {
            put("sessionId", wavSid)
            put("outputFormat", "WAV")
            put("outputFormatActual", "WAV")
            put("audioFile", wavInternal.canonicalPath)
            put("exportedAudio", wavExported.canonicalPath)
            put("durationMs", 4000L)
            put("sha256", "dummy-sha-wav")
        }
        File(sessionDir, "$wavSid.json").writeText(wavMeta.toString(2))
        File(exportDir, "$wavSid.json").writeText(wavMeta.toString(2))

        val res3 = SavedAudioRepository.loadRecordingsFromDirs(sessionDir, exportDir)
        println("  Found items: ${res3.size} -> ${res3.map { "${it.name} (${it.format})" }}")
        check(res3.size == 3) { "Expected 3 items total (MP3 + M4A + WAV), got ${res3.size}" }
        val wavItem = res3.first { it.id == wavSid }
        check(wavItem.format == AudioOutputFormat.WAV) { "Expected format WAV, got ${wavItem.format}" }
        check(wavItem.name == "$wavSid.wav") { "Expected name $wavSid.wav, got ${wavItem.name}" }
        check(wavItem.file.canonicalPath == wavExported.canonicalPath) { "Expected primary file to be exported copy" }
        println("  PASS: WAV session produced exactly ONE .wav item. Exported duplicate correctly deduplicated.")

        // --- TEST 4: Interrupted Session (only WAV exists, transcode never ran) ---
        println("[Test 4] Simulating interrupted session (only master WAV, no transcode output)...")
        val intSid = "20261029-400000-004"
        val intWav = File(sessionDir, "$intSid.wav").apply { writeBytes(ByteArray(800) { 5 }) }
        val intMeta = JSONObject().apply {
            put("sessionId", intSid)
            put("outputFormat", "MP3")
            put("finalState", "INTERRUPTED")
            put("wavFile", intWav.canonicalPath)
            put("durationMs", 2000L)
        }
        File(sessionDir, "$intSid.json").writeText(intMeta.toString(2))

        val res4 = SavedAudioRepository.loadRecordingsFromDirs(sessionDir, exportDir)
        println("  Found items: ${res4.size} -> ${res4.map { "${it.name} (${it.format})" }}")
        check(res4.size == 4) { "Expected 4 items total, got ${res4.size}" }
        val intItem = res4.first { it.id == intSid }
        check(intItem.format == AudioOutputFormat.WAV) { "Interrupted session without MP3 should fall back to WAV, got ${intItem.format}" }
        check(intItem.file.canonicalPath == intWav.canonicalPath) { "Expected primary file to be internal WAV" }
        println("  PASS: Interrupted session without transcode safely fell back to primary WAV.")

        // --- TEST 5: Standalone files without metadata ---
        println("[Test 5] Simulating standalone files without metadata JSON...")
        val standSid = "20261029-500000-005"
        val standWav = File(sessionDir, "$standSid.wav").apply { writeBytes(ByteArray(600) { 7 }) }
        val standMp3Internal = File(sessionDir, "$standSid.mp3").apply { writeBytes(ByteArray(150) { 8 }) }
        val standMp3Exported = File(exportDir, "$standSid.mp3").apply { writeBytes(ByteArray(150) { 8 }) }

        val res5 = SavedAudioRepository.loadRecordingsFromDirs(sessionDir, exportDir)
        check(res5.size == 5) { "Expected 5 items total, got ${res5.size}" }
        val standItem = res5.first { it.id == standSid }
        check(standItem.format == AudioOutputFormat.MP3) { "Expected format MP3, got ${standItem.format}" }
        check(standItem.masterWavFile?.canonicalPath == standWav.canonicalPath) { "Expected masterWavFile to be recognized" }
        println("  PASS: Standalone unassociated session without JSON correctly deduplicated to 1 MP3.")

        println("\nALL 5 REGRESSION TESTS PASSED SUCCESSFULLY!")
    } finally {
        tempDir.deleteRecursively()
    }
}
