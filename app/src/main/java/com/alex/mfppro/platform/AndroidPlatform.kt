package com.alex.mfppro.platform

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.alex.mfppro.data.KvStore
import com.alex.mfppro.data.Log
import com.alex.mfppro.data.Nicknames
import com.alex.mfppro.data.Prefs
import com.alex.mfppro.data.Repo

/** Builds the shared [Repo] on Android: logcat, (encrypted) SharedPreferences, process lifecycle, network changes. */
fun androidRepo(ctx: Context): Repo {
    Log.sink = { level, tag, msg ->
        when (level) {
            'E' -> android.util.Log.e(tag, msg)
            'W' -> android.util.Log.w(tag, msg)
            'I' -> android.util.Log.i(tag, msg)
            else -> android.util.Log.d(tag, msg)
        }
    }
    Nicknames.factory = { mutableStateMapOf() }

    val secure = EncryptedSharedPreferences.create(
        ctx,
        "secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    val plain = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
    val repo = Repo(Prefs(SharedPrefsStore(secure), SharedPrefsStore(plain)))

    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = repo.setVisible(true)
        override fun onStop(owner: LifecycleOwner) = repo.setVisible(false)
    })
    var first = true
    ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (first) { first = false; return }
            repo.networkChanged()
        }
    })
    return repo
}

/** [KvStore] over SharedPreferences. */
class SharedPrefsStore(private val p: SharedPreferences) : KvStore {
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getLong(key: String, default: Long) = p.getLong(key, default)
    override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
    override fun contains(key: String) = p.contains(key)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? = p.getStringSet(key, default)
    override val all: Map<String, *> get() = p.all
    override fun edit(): KvStore.Editor = Ed(p.edit())

    private class Ed(private val e: SharedPreferences.Editor) : KvStore.Editor {
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun putStringSet(key: String, value: Set<String>?) = apply { e.putStringSet(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun apply() = e.apply()
        override fun commit() = e.commit()
    }
}
