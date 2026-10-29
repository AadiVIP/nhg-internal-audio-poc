package ai.nhg.capturepoc

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nhg_recorder_prefs", Context.MODE_PRIVATE)

    var outputFormat: AudioOutputFormat
        get() {
            val id = prefs.getString(KEY_OUTPUT_FORMAT, AudioOutputFormat.WAV.id)
            val format = AudioOutputFormat.fromId(id)
            return if (format.isAvailable) format else AudioOutputFormat.WAV
        }
        set(value) {
            prefs.edit().putString(KEY_OUTPUT_FORMAT, value.id).apply()
        }

    var floatingControlsEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_CONTROLS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FLOATING_CONTROLS, value).apply()
        }

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_DARK)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
        }

    companion object {
        private const val KEY_OUTPUT_FORMAT = "pref_output_format"
        private const val KEY_FLOATING_CONTROLS = "pref_floating_controls"
        private const val KEY_THEME_MODE = "pref_theme_mode"

        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2

        @Volatile private var instance: PreferencesManager? = null

        fun get(context: Context): PreferencesManager =
            instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
    }
}
