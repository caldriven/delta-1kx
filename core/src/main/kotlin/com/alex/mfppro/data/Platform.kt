package com.alex.mfppro.data

/**
 * The few things the shared data layer needs from the platform it runs on. Android plugs in
 * SharedPreferences and logcat; the desktop app plugs in JSON files (the key one encrypted with
 * Windows DPAPI) and stderr.
 */

/** Logger with the android.util.Log call shape, so shared code reads the same on every platform. */
object Log {
    /** Where lines go: level ('D', 'I', 'W', 'E'), tag, message. */
    @Volatile var sink: (level: Char, tag: String, msg: String) -> Unit = { l, t, m -> System.err.println("$l/$t: $m") }

    fun d(tag: String, msg: String) = sink('D', tag, msg)
    fun i(tag: String, msg: String) = sink('I', tag, msg)
    fun w(tag: String, msg: String) = sink('W', tag, msg)
    fun e(tag: String, msg: String) = sink('E', tag, msg)
}

/** A key-value store with the SharedPreferences API subset [Prefs] uses. */
interface KvStore {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    operator fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putStringSet(key: String, value: Set<String>?): Editor
        fun remove(key: String): Editor
        /** Saves in the background where the platform can. */
        fun apply()
        /** Saves now; true when it was written. */
        fun commit(): Boolean
    }
}
