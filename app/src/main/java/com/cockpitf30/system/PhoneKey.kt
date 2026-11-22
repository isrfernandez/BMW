package com.cockpitf30.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Un móvil visto por la radio: nombre y, si se sabe, la dirección Bluetooth. */
data class SeenPhone(val name: String?, val address: String?, val source: String) {
    /** Lo que se guarda como llave (la dirección si la hay, si no el nombre). */
    val id: String? get() = address?.uppercase() ?: name?.trim()?.takeIf { it.isNotEmpty() }
    val label: String get() = name ?: address ?: "móvil"
}

/**
 * La llave del Capi: su móvil conectado por Bluetooth. Para borrar su cuenta hacen falta el PIN y
 * que ese móvil esté conectado a la radio.
 *
 * Esta radio lleva un módulo Bluetooth propio (goc) para el teléfono, que puede no verse desde el
 * Bluetooth de Android: por eso se mira en los dos sitios (Android y los datos del Bluetooth de la
 * radio). Si la radio no deja ver ningún móvil, no se puede registrar llave y el borrado sigue
 * pidiendo solo el PIN (Ajustes lo dice).
 */
object PhoneKey {
    private const val KEY = "capi_phone_key"
    private const val KEY_LABEL = "capi_phone_label"
    private val RADIO_BT = listOf("content://carbt/device", "content://carbt/connect", "content://carbt/state", "content://carbt/paired")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    fun registered(ctx: Context): String? = prefs(ctx).getString(KEY_LABEL, null)?.takeIf { prefs(ctx).contains(KEY) }

    fun register(ctx: Context, phone: SeenPhone): Boolean {
        val id = phone.id ?: return false
        prefs(ctx).edit().putString(KEY, hash(id)).putString(KEY_LABEL, phone.label).apply()
        return true
    }

    fun forget(ctx: Context) { prefs(ctx).edit().remove(KEY).remove(KEY_LABEL).apply() }

    /** ¿Está conectada la llave? true también si no hay llave registrada (entonces solo vale el PIN). */
    suspend fun present(ctx: Context): Boolean {
        val k = prefs(ctx).getString(KEY, null) ?: return true
        return connected(ctx).any { p -> p.id?.let { hash(it) } == k }
    }

    /** Móviles conectados ahora mismo. */
    @SuppressLint("MissingPermission")
    suspend fun connected(ctx: Context): List<SeenPhone> {
        val out = ArrayList<SeenPhone>()
        // 1) Bluetooth de Android: manos libres y audio.
        runCatching {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
                    val done = CompletableDeferred<List<SeenPhone>>()
                    adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            val list = runCatching { proxy.connectedDevices.map { SeenPhone(it.name, it.address, "Android") } }.getOrDefault(emptyList())
                            runCatching { adapter.closeProfileProxy(p, proxy) }
                            done.complete(list)
                        }
                        override fun onServiceDisconnected(p: Int) { done.complete(emptyList()) }
                    }, profile)
                    withTimeoutOrNull(1500) { done.await() }?.let { out += it }
                }
            }
        }
        // 2) El Bluetooth de la radio (módulo propio): sus tablas y ajustes.
        for (u in RADIO_BT) runCatching {
            ctx.contentResolver.query(Uri.parse(u), null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    var name: String? = null; var addr: String? = null
                    for (i in 0 until c.columnCount) {
                        val col = c.getColumnName(i).lowercase()
                        val v = runCatching { c.getString(i) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                        if (addr == null && (col.contains("addr") || col.contains("mac")) && MAC.matches(v)) addr = v
                        if (name == null && col.contains("name")) name = v
                    }
                    if (name != null || addr != null) out += SeenPhone(name, addr, u)
                }
            }
        }
        runCatching {
            ctx.contentResolver.query(Settings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { c ->
                var name: String? = null; var addr: String? = null
                while (c.moveToNext()) {
                    val k = c.getString(0)?.lowercase() ?: continue
                    val v = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    if (!(k.contains("bt") || k.contains("bluetooth")) || !(k.contains("connect") || k.contains("phone") || k.contains("remote"))) continue
                    if (addr == null && MAC.matches(v)) addr = v
                    else if (name == null && k.contains("name") && v.length in 2..40) name = v
                }
                if (name != null || addr != null) out += SeenPhone(name, addr, "Ajustes de la radio")
            }
        }
        return out.distinctBy { it.id }
    }

    private val MAC = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    private fun hash(id: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(("athoox-capi:" + id).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
