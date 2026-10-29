package ai.nhg.capturepoc

enum class AudioOutputFormat(
    val id: String,
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val description: String,
    val badgeLabel: String
) {
    WAV(
        id = "wav",
        displayName = "WAV",
        extension = "wav",
        mimeType = "audio/wav",
        description = "Lossless 48 kHz stereo 16-bit PCM. Maximum quality, direct capture with zero re-encoding.",
        badgeLabel = "WAV"
    ),
    M4A(
        id = "m4a",
        displayName = "M4A (AAC)",
        extension = "m4a",
        mimeType = "audio/mp4",
        description = "Compressed 48 kHz stereo AAC-LC at 192 kbps. Compact file size, universal playback.",
        badgeLabel = "M4A"
    ),
    MP3(
        id = "mp3",
        displayName = "MP3",
        extension = "mp3",
        mimeType = "audio/mpeg",
        description = "Compressed 48 kHz stereo MP3 at 192 kbps, encoded offline with the bundled LAME 4.0 library.",
        badgeLabel = "MP3"
    );

    val isAvailable: Boolean
        get() = this != MP3 || Mp3Encoder.isAvailable()

    companion object {
        fun fromId(id: String?): AudioOutputFormat =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: WAV

        fun fromExtension(ext: String?): AudioOutputFormat =
            values().firstOrNull { it.extension.equals(ext, ignoreCase = true) } ?: WAV
    }
}
