package com.alex.mfppro.desktop

import com.alex.mfppro.data.KvStore
import com.alex.mfppro.data.Log
import com.alex.mfppro.data.Prefs
import com.alex.mfppro.data.Repo
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/**
 * Where settings live. Portable mode: a `data` folder next to Delta1kx.exe keeps everything with
 * the app (a USB stick, a synced folder). Otherwise %APPDATA%\Delta1kx. `-Dmfp.dataDir=` overrides both.
 */
fun dataDir(): File {
    System.getProperty("mfp.dataDir")?.let { return File(it) }
    System.getProperty("jpackage.app-path")?.let { exe ->
        File(exe).parentFile?.resolve("data")?.takeIf { it.isDirectory }?.let { return it }
    }
    val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
    return base.resolve("Delta1kx")
}

/** Builds the shared [Repo] on desktop: a log file, JSON settings, the key file encrypted with DPAPI. */
fun desktopRepo(dir: File): Repo {
    dir.mkdirs()
    val log = runCatching { PrintWriter(dir.resolve("delta1kx.log").bufferedWriter(), true) }.getOrNull()
    val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    Log.sink = { level, tag, msg ->
        val line = "${LocalTime.now().format(time)} $level/$tag: $msg"
        System.err.println(line)
        log?.println(line)
    }
    return Repo(
        Prefs(
            secure = JsonFileStore(dir.resolve("secure.dat"), protect = true),
            plain = JsonFileStore(dir.resolve("settings.json"), protect = false),
        )
    )
}

/**
 * Windows DPAPI: the key file can only be decrypted by the same Windows user on the same PC.
 * A portable folder copied to another machine asks for the key again instead of leaking it.
 */
private object Dpapi {
    fun protect(b: ByteArray): ByteArray = if (isWindows) Crypt32Util.cryptProtectData(b) else b
    fun unprotect(b: ByteArray): ByteArray = if (isWindows) Crypt32Util.cryptUnprotectData(b) else b
}

/** [KvStore] kept in one JSON file, written atomically on every change. [protect] encrypts it with DPAPI. */
class JsonFileStore(private val file: File, private val protect: Boolean) : KvStore {
    private val lock = Any()
    private val map = LinkedHashMap<String, JsonElement>()

    init {
        if (file.exists()) runCatching {
            val bytes = file.readBytes().let { if (protect) Dpapi.unprotect(it) else it }
            (Json.parseToJsonElement(bytes.decodeToString()) as JsonObject).forEach { (k, v) -> map[k] = v }
        }.onFailure { Log.w("Store", "${file.name} unreadable, starting empty: ${it.message}") }
    }

    private fun prim(key: String) = synchronized(lock) { map[key] as? JsonPrimitive }

    override fun getString(key: String, default: String?) = prim(key)?.takeIf { it.isString }?.content ?: default
    override fun getInt(key: String, default: Int) = prim(key)?.intOrNull ?: default
    override fun getLong(key: String, default: Long) = prim(key)?.longOrNull ?: default
    override fun getFloat(key: String, default: Float) = prim(key)?.floatOrNull ?: default
    override fun getBoolean(key: String, default: Boolean) = prim(key)?.booleanOrNull ?: default
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        synchronized(lock) { (map[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() } ?: default
    override fun contains(key: String) = synchronized(lock) { key in map }

    override val all: Map<String, *>
        get() = synchronized(lock) {
            map.mapValues { (_, v) ->
                when (v) {
                    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                    is JsonPrimitive -> if (v.isString) v.content else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull
                    else -> null
                }
            }
        }

    override fun edit(): KvStore.Editor = Ed()

    private inner class Ed : KvStore.Editor {
        private val ops = ArrayList<(MutableMap<String, JsonElement>) -> Unit>()
        private fun op(f: (MutableMap<String, JsonElement>) -> Unit) = apply { ops += f }

        override fun putString(key: String, value: String?) = op { if (value == null) it.remove(key) else it[key] = JsonPrimitive(value) }
        override fun putInt(key: String, value: Int) = op { it[key] = JsonPrimitive(value) }
        override fun putLong(key: String, value: Long) = op { it[key] = JsonPrimitive(value) }
        override fun putFloat(key: String, value: Float) = op { it[key] = JsonPrimitive(value) }
        override fun putBoolean(key: String, value: Boolean) = op { it[key] = JsonPrimitive(value) }
        override fun putStringSet(key: String, value: Set<String>?) =
            op { if (value == null) it.remove(key) else it[key] = JsonArray(value.map(::JsonPrimitive)) }
        override fun remove(key: String) = op { it.remove(key) }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(lock) {
            ops.forEach { it(map) }
            runCatching {
                file.parentFile?.mkdirs()
                val bytes = JsonObject(map).toString().encodeToByteArray().let { if (protect) Dpapi.protect(it) else it }
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(bytes)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { Log.e("Store", "saving ${file.name}: ${it.message}") }.isSuccess
        }
    }
}
