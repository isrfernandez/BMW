package com.cockpitf30.system

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.cockpitf30.vehicle.source.VendorCar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ── Estado ───────────────────────────────────────────────────────────────────

data class WifiNet(
    val ssid: String,
    val rssi: Int,
    /** 0–4 rayas. */
    val level: Int,
    val secure: Boolean,
    val security: String,
    val freqMhz: Int,
    val connected: Boolean = false,
    /** Guardada desde Athoox (se puede olvidar desde aquí). */
    val ours: Boolean = false,
)

data class WifiState(
    val enabled: Boolean? = null,
    val ssid: String? = null,
    val rssi: Int? = null,
    val level: Int = 0,
    val linkMbps: Int? = null,
    val freqMhz: Int? = null,
    val ip: String? = null,
    val networks: List<WifiNet> = emptyList(),
    val scanning: Boolean = false,
    val scannedAt: Long = 0L,
)

data class BtDev(val name: String, val addr: String?, val connected: Boolean = false)

enum class BtSource { RADIO, ANDROID, NONE }

data class BtState(
    val source: BtSource = BtSource.NONE,
    val powered: Boolean? = null,
    /** Estado del manos libres: Conectado, Conectando, En llamada… */
    val hfp: String? = null,
    val connectedName: String? = null,
    val connectedAddr: String? = null,
    val localName: String? = null,
    val pin: String? = null,
    val paired: List<BtDev> = emptyList(),
    val found: List<BtDev> = emptyList(),
    val searching: Boolean = false,
    /** Claves del Bluetooth de la radio tal cual (para el Capi: ver qué manda). */
    val raw: String = "",
)

data class CellState(
    val sim: String = "—",
    val simReady: Boolean = false,
    val operator: String? = null,
    /** «4G», «3G»… */
    val tech: String? = null,
    val level: Int = 0,
    val dbm: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val snr: Int? = null,
    val dataOn: Boolean? = null,
    val roaming: Boolean = false,
    /** Últimos dBm (cada 2 s) para la gráfica. */
    val history: List<Int> = emptyList(),
)

data class Sat(val svid: Int, val constellation: String, val cn0: Float, val used: Boolean)

data class GpsState(
    val permission: Boolean = false,
    val enabled: Boolean = false,
    val sats: List<Sat> = emptyList(),
    val location: Location? = null,
    val firstFixMs: Int? = null,
) {
    val inView get() = sats.size
    val used get() = sats.count { it.used }
    val ageS get() = location?.let { ((System.currentTimeMillis() - it.time) / 1000).coerceAtLeast(0) }
    val fixed get() = location != null && (ageS ?: 99) < 10
}

data class NetState(val via: String = "Sin conexión", val internet: Boolean = false)

data class ConnState(
    val net: NetState = NetState(),
    val wifi: WifiState = WifiState(),
    val bt: BtState = BtState(),
    val cell: CellState = CellState(),
    val gps: GpsState = GpsState(),
    /** Último aviso para la pantalla («Conectando a Casa…»). */
    val toast: String? = null,
)

/**
 * Conexiones de la radio: Wi-Fi, Bluetooth, datos móviles (SIM 4G) y GPS, sin salir de Athoox.
 *
 * Lo que deja hacer Android 10 a una app normal (la radio es API 29 y Athoox apunta a 34):
 * - Wi-Fi: buscar y ver redes sí. Encender/apagar no (setWifiEnabled no hace nada): se abre el
 *   panel del sistema. Conectar va como «red sugerida»: la radio la guarda y se conecta sola (la
 *   primera vez Android pregunta si se permiten las sugerencias de Athoox). Olvidar, solo las que
 *   se guardaron desde aquí; las del sistema, en sus ajustes.
 * - Bluetooth: el teléfono va por el módulo propio de la radio (goc), no por el Bluetooth de
 *   Android. Se maneja con las mismas peticiones que su app de Bluetooth (request.bt.*: buscar,
 *   conectar, desconectar, olvidar, encender) por el servicio del coche. Si no responde, se usa el
 *   Bluetooth de Android (emulador) y, si tampoco, se abre la app de Bluetooth de la radio.
 * - SIM: intensidad, operador y tipo de red sí; encender o apagar los datos no (ajustes).
 * - GPS: satélites, precisión y posición, todo legible.
 */
object Connectivity {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ConnState())
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private var users = 0
    private var loop: Job? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())

    private const val PREFS = "cockpit"
    private const val KEY_OURS = "wifi_suggested"
    private val MAC = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")

    /** La app de Bluetooth de la radio (vista en el lanzador de serie). */
    private val RADIO_BT_APP = ComponentName("android.car.app.bt", "android.car.app.bt.BtMain")

    // ── Ciclo ────────────────────────────────────────────────────────────────

    /** Empieza a vigilar (lo llama la pantalla al abrirse). Cada [start] lleva su [stop]. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        if (users++ > 0) return
        registerWifi(ctx)
        registerBt(ctx)
        registerCell(ctx)
        registerGps(ctx)
        btQuery()
        scanWifi()
        loop = scope.launch {
            while (isActive) {
                refresh(ctx)
                delay(2000)
            }
        }
    }

    fun stop() {
        if (users == 0 || --users > 0) return
        loop?.cancel(); loop = null
        val ctx = app ?: return
        receivers.forEach { runCatching { ctx.unregisterReceiver(it) } }
        receivers.clear()
        phoneListener?.let { l -> runCatching { ctx.getSystemService(TelephonyManager::class.java)?.listen(l, PhoneStateListener.LISTEN_NONE) } }
        phoneListener = null
        val lm = ctx.getSystemService(LocationManager::class.java)
        gnssCb?.let { runCatching { lm?.unregisterGnssStatusCallback(it) } }
        gnssCb = null
        runCatching { lm?.removeUpdates(gpsListener) }
        runCatching { androidAdapter(ctx)?.cancelDiscovery() }
    }

    private val receivers = ArrayList<BroadcastReceiver>()

    private fun register(ctx: Context, filter: IntentFilter, onReceive: (Intent) -> Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = onReceive(i) }
        runCatching { ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_EXPORTED); receivers += r }
    }

    fun has(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Permisos que faltan para ver todo (se piden al abrir la pantalla). */
    fun missingPermissions(ctx: Context): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
    }.filterNot { has(ctx, it) }

    /** Al conceder permisos: se vuelve a enganchar lo que los necesitaba. */
    fun permissionsChanged(context: Context) {
        val ctx = context.applicationContext
        if (users == 0) return
        registerGps(ctx)
        registerCell(ctx)
        scanWifi()
    }

    private fun toast(text: String) {
        _state.update { it.copy(toast = text) }
        scope.launch { delay(6000); _state.update { s -> if (s.toast == text) s.copy(toast = null) else s } }
    }

    private suspend fun refresh(ctx: Context) {
        val net = runCatching { readNet(ctx) }.getOrDefault(NetState())
        val wifi = runCatching { readWifi(ctx, _state.value.wifi) }.getOrDefault(_state.value.wifi)
        val bt = withContext(Dispatchers.IO) { runCatching { readBt(ctx, _state.value.bt) }.getOrDefault(_state.value.bt) }
        val cell = runCatching { readCell(ctx, _state.value.cell) }.getOrDefault(_state.value.cell)
        val gps = runCatching { readGps(ctx, _state.value.gps) }.getOrDefault(_state.value.gps)
        _state.update { it.copy(net = net, wifi = wifi, bt = bt, cell = cell, gps = gps) }
    }

    /** Resumen rápido para la tarjeta del Inicio (sin vigilar nada). */
    fun snapshot(context: Context): ConnState {
        val ctx = context.applicationContext
        val s = _state.value
        return s.copy(
            net = runCatching { readNet(ctx) }.getOrDefault(s.net),
            wifi = runCatching { readWifi(ctx, s.wifi) }.getOrDefault(s.wifi),
            cell = runCatching { readCell(ctx, s.cell, keepHistory = false) }.getOrDefault(s.cell),
        )
    }

    // ── Red activa ───────────────────────────────────────────────────────────

    private fun readNet(ctx: Context): NetState {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return NetState()
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return NetState()
        val via = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos móviles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "cable"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "otra red"
        }
        return NetState(via, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    // ── Wi-Fi ────────────────────────────────────────────────────────────────

    private fun wm(ctx: Context) = ctx.applicationContext.getSystemService(WifiManager::class.java)

    private fun registerWifi(ctx: Context) {
        register(ctx, IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }) { i ->
            if (i.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) _state.update { it.copy(wifi = it.wifi.copy(scanning = false, scannedAt = System.currentTimeMillis())) }
            scope.launch { _state.update { s -> s.copy(wifi = runCatching { readWifi(ctx, s.wifi) }.getOrDefault(s.wifi), net = runCatching { readNet(ctx) }.getOrDefault(s.net)) } }
        }
    }

    fun scanWifi() {
        val ctx = app ?: return
        @Suppress("DEPRECATION")
        val ok = runCatching { wm(ctx)?.startScan() == true }.getOrDefault(false)
        _state.update { it.copy(wifi = it.wifi.copy(scanning = ok)) }
        if (ok) scope.launch { delay(12_000); _state.update { s -> s.copy(wifi = s.wifi.copy(scanning = false)) } }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readWifi(ctx: Context, old: WifiState): WifiState {
        val wm = wm(ctx) ?: return old.copy(enabled = null)
        val enabled = wm.isWifiEnabled
        val info = if (enabled) wm.connectionInfo else null
        val ssid = info?.ssid?.trim('"')?.takeIf { info.networkId != -1 && it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.trim('<', '>') && it != "<unknown ssid>" }
        val ip = info?.ipAddress?.takeIf { it != 0 }?.let { "%d.%d.%d.%d".format(it and 0xFF, it shr 8 and 0xFF, it shr 16 and 0xFF, it shr 24 and 0xFF) }
        val ours = oursSet(ctx)
        val results: List<ScanResult> = if (enabled && has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) runCatching { wm.scanResults }.getOrNull().orEmpty() else emptyList()
        val nets = results.filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { (name, list) ->
                val best = list.maxBy { it.level }
                val sec = security(best.capabilities)
                WifiNet(name, best.level, level(best.level), sec != "Abierta", sec, best.frequency, connected = name == ssid, ours = name in ours)
            }
            .sortedWith(compareByDescending<WifiNet> { it.connected }.thenByDescending { it.ours }.thenByDescending { it.rssi })
        return old.copy(
            enabled = enabled,
            ssid = ssid,
            rssi = info?.rssi?.takeIf { ssid != null },
            level = info?.rssi?.takeIf { ssid != null }?.let(::level) ?: 0,
            linkMbps = info?.linkSpeed?.takeIf { ssid != null && it > 0 },
            freqMhz = info?.frequency?.takeIf { ssid != null && it > 0 },
            ip = ip?.takeIf { ssid != null },
            networks = nets,
        )
    }

    fun level(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -66 -> 3
        rssi >= -77 -> 2
        rssi >= -88 -> 1
        else -> 0
    }

    private fun security(caps: String): String = when {
        caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
        caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        caps.contains("WEP") -> "WEP"
        caps.contains("EAP") -> "Empresa"
        else -> "Abierta"
    }

    private fun oursSet(ctx: Context): Set<String> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OURS, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).getString("ssid") }.toSet()
    }.getOrDefault(emptySet())

    private fun oursList(ctx: Context): List<JSONObject> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OURS, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun saveOurs(ctx: Context, list: List<JSONObject>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OURS, JSONArray(list).toString()).apply()
    }

    /** Encender o apagar el Wi-Fi. En Android 10 solo lo deja el sistema: se abre su panel. */
    fun setWifi(activity: Context, on: Boolean) {
        @Suppress("DEPRECATION")
        val ok = runCatching { wm(activity)?.setWifiEnabled(on) == true }.getOrDefault(false)
        if (!ok) {
            openWifiPanel(activity)
            toast(if (on) "Enciende el Wi-Fi en el panel de la radio" else "Apaga el Wi-Fi en el panel de la radio")
        }
    }

    fun openWifiPanel(activity: Context) {
        val panel = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else null
        open(activity, listOfNotNull(panel, Intent(Settings.ACTION_WIFI_SETTINGS)))
    }

    /**
     * Conectar a [net] con [password] (null si es abierta). Primero la vía antigua (funciona si la
     * radio nos trata como app del sistema o es Android 9 o menos); si no, red sugerida.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun connectWifi(activity: Context, net: WifiNet, password: String?) {
        val ctx = activity.applicationContext
        val wm = wm(ctx) ?: return
        val pass = password?.takeIf { it.isNotEmpty() }
        // 1) Vía antigua.
        val legacy = runCatching {
            val conf = WifiConfiguration().apply {
                SSID = "\"${net.ssid}\""
                when {
                    !net.secure -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    net.security == "WEP" -> { wepKeys[0] = "\"$pass\""; allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) }
                    else -> preSharedKey = "\"$pass\""
                }
            }
            val id = wm.addNetwork(conf)
            id != -1 && wm.enableNetwork(id, true)
        }.getOrDefault(false)
        if (legacy) {
            toast("Conectando a ${net.ssid}…")
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            toast("La radio no deja conectar desde Athoox: ábrelo en el Wi-Fi del sistema")
            openWifiPanel(activity)
            return
        }
        // 2) Red sugerida.
        val sugg = suggestion(net.ssid, net.security, pass) ?: run {
            toast("Las redes «${net.security}» se configuran en el Wi-Fi del sistema"); openWifiPanel(activity); return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: diálogo del sistema «¿Guardar esta red?», que además conecta.
            val i = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(sugg))
            if (open(activity, listOf(i))) { toast("Confirma «Guardar» y la radio se conecta a ${net.ssid}"); return }
        }
        val old = oursList(ctx).filter { it.optString("ssid") == net.ssid }
        old.mapNotNull { suggestion(it.optString("ssid"), it.optString("sec"), it.optString("pass").ifEmpty { null }) }
            .takeIf { it.isNotEmpty() }?.let { runCatching { wm.removeNetworkSuggestions(it) } }
        val status = runCatching { wm.addNetworkSuggestions(listOf(sugg)) }.getOrDefault(-1)
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            saveOurs(ctx, oursList(ctx).filter { it.optString("ssid") != net.ssid } + JSONObject().put("ssid", net.ssid).put("sec", net.security).put("pass", pass ?: ""))
            toast("${net.ssid} guardada: la radio se conecta sola en unos segundos (si pregunta, permite las redes de Athoox)")
            // Para que el sistema la vea en cuanto pueda.
            scanWifi()
        } else {
            toast("La radio no aceptó la red (código $status): ábrela en el Wi-Fi del sistema")
            openWifiPanel(activity)
        }
    }

    private fun suggestion(ssid: String, sec: String, pass: String?): WifiNetworkSuggestion? {
        if (Build.VERSION.SDK_INT < 29) return null
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
        when (sec) {
            "Abierta" -> Unit
            "WPA3" -> b.setWpa3Passphrase(pass ?: return null)
            "WPA", "WPA2" -> b.setWpa2Passphrase(pass ?: return null)
            else -> return null
        }
        return runCatching { b.build() }.getOrNull()
    }

    /** Olvidar una red. Solo se pueden quitar las que guardó Athoox; las del sistema, en sus ajustes. */
    fun forgetWifi(activity: Context, net: WifiNet) {
        val ctx = activity.applicationContext
        val mine = oursList(ctx).filter { it.optString("ssid") == net.ssid }
        if (mine.isNotEmpty() && Build.VERSION.SDK_INT >= 29) {
            val list = mine.mapNotNull { suggestion(it.optString("ssid"), it.optString("sec"), it.optString("pass").ifEmpty { null }) }
            runCatching { wm(ctx)?.removeNetworkSuggestions(list) }
            saveOurs(ctx, oursList(ctx).filter { it.optString("ssid") != net.ssid })
            toast("${net.ssid} olvidada")
            return
        }
        toast("Esta red la guardó el sistema: olvídala en el Wi-Fi de la radio")
        openWifiPanel(activity)
    }

    // ── Bluetooth ────────────────────────────────────────────────────────────

    private fun androidAdapter(ctx: Context): BluetoothAdapter? =
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    private val foundAndroid = LinkedHashMap<String, BtDev>()
    private val connectedAndroid = HashSet<String>()

    @SuppressLint("MissingPermission")
    private fun registerBt(ctx: Context) {
        register(ctx, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }) { i ->
            @Suppress("DEPRECATION")
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> d?.let { dev -> foundAndroid[dev.address] = BtDev(runCatching { dev.name }.getOrNull() ?: dev.address, dev.address) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _state.update { it.copy(bt = it.bt.copy(searching = false)) }
                BluetoothDevice.ACTION_ACL_CONNECTED -> d?.let { connectedAndroid += it.address }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> d?.let { connectedAndroid -= it.address }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (d != null && i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0) == BluetoothDevice.BOND_BONDED) toast("Emparejado con ${runCatching { d.name }.getOrNull() ?: d.address}")
            }
        }
    }

    private fun car(ctx: Context): VendorCar? = VendorCar.connect(ctx).getOrNull()

    private fun radioReq(key: String, args: Bundle? = null): Boolean {
        val ctx = app ?: return false
        val c = car(ctx) ?: return false
        return runCatching { c.requestOneway(key, args); true }.getOrDefault(false)
    }

    private fun btQuery() { scope.launch(Dispatchers.IO) { radioReq("request.bt.query_pair_list") } }

    private val HFP = listOf("Iniciando", "Libre", "Conectando", "Conectado", "Llamando", "Llamada entrante", "En llamada")

    @SuppressLint("MissingPermission")
    private fun readBt(ctx: Context, old: BtState): BtState {
        // 1) Bluetooth de la radio (módulo goc) por el servicio del coche.
        val vals = car(ctx)?.let { c -> runCatching { c.read(listOf("state.bt.", "data.bt.")) }.getOrNull() }.orEmpty()
        if (vals.isNotEmpty()) {
            val power = intOf(vals["state.bt.power_state"])
            val hfp = intOf(vals["state.bt.hfp_state"])
            val connected = hfp != null && hfp >= 3
            val name = (vals["state.bt.remote_name"] as? String)?.takeIf { it.isNotBlank() && connected }
            val addr = (vals["state.bt.remote_addr"] as? String)?.takeIf { it.isNotBlank() && connected }
            val paired = devices(vals["state.bt.pair_list"]).map { it.copy(connected = connected && (it.addr != null && it.addr.equals(addr, true) || it.name == name)) }
            val found = devices(vals["state.bt.find_phone"]).filter { f -> paired.none { p -> p.addr != null && p.addr.equals(f.addr, true) } }
            val search = vals["state.bt.search_state"]
            return old.copy(
                source = BtSource.RADIO,
                powered = power?.let { it == 1 },
                hfp = hfp?.let { HFP.getOrNull(it) },
                connectedName = name ?: addr?.let { a -> paired.firstOrNull { it.addr.equals(a, true) }?.name },
                connectedAddr = addr,
                localName = (vals["state.bt.local_name"] ?: vals["data.bt.local_name"]) as? String,
                pin = (vals["state.bt.pin"] ?: vals["data.bt.local_pin"]) as? String,
                paired = paired,
                found = found,
                searching = when (search) { is Int -> search != 0; is Boolean -> search; else -> old.searching },
                raw = vals.entries.filter { it.key.startsWith("state.bt.") && it.key !in NOISY }.joinToString("\n") { "${it.key} = ${com.cockpitf30.vehicle.source.RadioCarData.describe(it.value).take(160)}" },
            )
        }
        // 2) Bluetooth de Android.
        val a = androidAdapter(ctx) ?: return old.copy(source = BtSource.NONE)
        if (Build.VERSION.SDK_INT >= 31 && !has(ctx, Manifest.permission.BLUETOOTH_CONNECT)) return old.copy(source = BtSource.ANDROID, powered = null)
        val on = runCatching { a.isEnabled }.getOrDefault(false)
        val bonded = if (on) runCatching { a.bondedDevices.toList() }.getOrDefault(emptyList()) else emptyList()
        val paired = bonded.map { d ->
            val c = d.address in connectedAndroid || runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
            BtDev(runCatching { d.name }.getOrNull() ?: d.address, d.address, c)
        }
        val conn = paired.firstOrNull { it.connected }
        return old.copy(
            source = BtSource.ANDROID,
            powered = on,
            hfp = if (conn != null) "Conectado" else null,
            connectedName = conn?.name, connectedAddr = conn?.addr,
            localName = runCatching { a.name }.getOrNull(),
            paired = paired,
            found = foundAndroid.values.filter { f -> paired.none { it.addr == f.addr } },
            searching = runCatching { a.isDiscovering }.getOrDefault(false),
            raw = "",
        )
    }

    private val NOISY = setOf("state.bt.album_art", "state.bt.avrcp_info", "state.bt.phonebook_comm_list", "state.bt.phonebook_collect_list", "state.bt.phonebook_sos_list")

    private fun intOf(v: Any?): Int? = when (v) {
        is Int -> v
        is Number -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /**
     * Lista de aparatos del Bluetooth de la radio. No sabemos su formato (no está documentado):
     * se aceptan JSON, Bundle, arrays y texto con direcciones MAC.
     */
    fun devices(v: Any?): List<BtDev> {
        v ?: return emptyList()
        return runCatching {
            when (v) {
                is String -> fromText(v)
                is Bundle -> fromBundle(v)
                is Array<*> -> v.flatMap { item -> when (item) { is String -> fromText(item); is Bundle -> fromBundle(item); null -> emptyList(); else -> listOfNotNull(fromObject(item)) } }
                is Collection<*> -> devices(v.toTypedArray())
                else -> listOfNotNull(fromObject(v))
            }
        }.getOrDefault(emptyList()).distinctBy { (it.addr ?: it.name).uppercase() }
    }

    private fun fromText(s: String): List<BtDev> {
        val t = s.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("[") || t.startsWith("{")) runCatching {
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { o -> o.keys().asSequence().map { o.get(it) }.firstOrNull { it is JSONArray } as? JSONArray ?: JSONArray().put(o) }
            return (0 until arr.length()).mapNotNull { i ->
                when (val o = arr.get(i)) {
                    is JSONObject -> {
                        val addr = o.keys().asSequence().firstOrNull { it.contains("addr", true) || it.contains("mac", true) }?.let { o.optString(it) }
                        val name = o.keys().asSequence().firstOrNull { it.contains("name", true) }?.let { o.optString(it) }
                        if (addr.isNullOrBlank() && name.isNullOrBlank()) null else BtDev(name?.ifBlank { null } ?: addr!!, addr?.ifBlank { null })
                    }
                    is String -> fromText(o).firstOrNull()
                    else -> null
                }
            }
        }
        // Texto: cada aparato en un trozo («nombre,addr;nombre,addr» o líneas).
        val chunks = t.split('\n', ';', '|').map { it.trim() }.filter { it.isNotEmpty() }
        val withMac = chunks.filter { MAC.containsMatchIn(it) }
        if (withMac.isNotEmpty()) return withMac.map { c ->
            val mac = MAC.find(c)!!.value.uppercase().replace('-', ':')
            val name = c.replace(MAC, "").trim(' ', ',', '=', ':', '-', '\t').ifBlank { mac }
            BtDev(name, mac)
        }
        // Par nombre/dirección en trozos alternos.
        val macs = MAC.findAll(t).map { it.value.uppercase() }.toList()
        return if (macs.isEmpty()) emptyList() else macs.map { BtDev(it, it) }
    }

    private fun fromBundle(b: Bundle): List<BtDev> {
        val keys = b.keySet()
        @Suppress("DEPRECATION")
        fun arr(k: String?): List<String>? = k?.let { b.get(it) }?.let { v -> when (v) { is Array<*> -> v.map { it?.toString().orEmpty() }; is List<*> -> v.map { it?.toString().orEmpty() }; else -> null } }
        val ak = keys.firstOrNull { it.contains("addr", true) || it.contains("mac", true) }
        val nk = keys.firstOrNull { it.contains("name", true) }
        val addrs = arr(ak); val names = arr(nk)
        if (addrs != null || names != null) {
            val n = maxOf(addrs?.size ?: 0, names?.size ?: 0)
            return (0 until n).map { i -> val a = addrs?.getOrNull(i)?.ifBlank { null }; BtDev(names?.getOrNull(i)?.ifBlank { null } ?: a ?: "?", a) }
        }
        @Suppress("DEPRECATION")
        val a = ak?.let { b.get(it)?.toString() }; @Suppress("DEPRECATION") val nm = nk?.let { b.get(it)?.toString() }
        if (a != null || nm != null) return listOf(BtDev(nm ?: a!!, a))
        @Suppress("DEPRECATION")
        return keys.flatMap { k -> devices(b.get(k)) }
    }

    private fun fromObject(o: Any): BtDev? {
        val fields = generateSequence<Class<*>>(o.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }.onEach { runCatching { it.isAccessible = true } }.toList()
        val a = fields.firstOrNull { it.name.contains("addr", true) || it.name.contains("mac", true) }?.let { runCatching { it.get(o)?.toString() }.getOrNull() }
        val n = fields.firstOrNull { it.name.contains("name", true) }?.let { runCatching { it.get(o)?.toString() }.getOrNull() }
        if (a == null && n == null) return fromText(o.toString()).firstOrNull()
        return BtDev(n ?: a!!, a)
    }

    @SuppressLint("MissingPermission")
    fun setBt(activity: Context, on: Boolean) {
        val s = _state.value.bt
        if (s.source == BtSource.RADIO && radioReq(if (on) "request.bt.power_on" else "request.bt.power_off")) {
            toast(if (on) "Encendiendo el Bluetooth…" else "Apagando el Bluetooth…"); return
        }
        val a = androidAdapter(activity)
        @Suppress("DEPRECATION")
        val ok = runCatching { if (on) a?.enable() == true else a?.disable() == true }.getOrDefault(false)
        if (ok) toast(if (on) "Encendiendo el Bluetooth…" else "Apagando el Bluetooth…")
        else { toast("Hazlo en el Bluetooth de la radio"); openBtApp(activity) }
    }

    @SuppressLint("MissingPermission")
    fun searchBt(activity: Context) {
        val s = _state.value.bt
        if (s.source == BtSource.RADIO) {
            val stop = s.searching
            if (radioReq(if (stop) "request.bt.stop_find_phone" else "request.bt.start_find_phone")) {
                _state.update { it.copy(bt = it.bt.copy(searching = !stop)) }
                if (!stop) { toast("Buscando… pon el móvil visible en su Bluetooth"); scope.launch { delay(30_000); _state.update { it.copy(bt = it.bt.copy(searching = false)) } } }
                return
            }
        }
        val a = androidAdapter(activity)
        val ok = runCatching { a?.cancelDiscovery(); foundAndroid.clear(); a?.startDiscovery() == true }.getOrDefault(false)
        if (ok) { _state.update { it.copy(bt = it.bt.copy(searching = true)) }; toast("Buscando… pon el móvil visible en su Bluetooth") }
        else { toast("No se puede buscar desde Athoox: abro el Bluetooth de la radio"); openBtApp(activity) }
    }

    /** Conectar (o emparejar, si es nuevo) [dev]. */
    @SuppressLint("MissingPermission")
    fun connectBt(activity: Context, dev: BtDev) {
        val s = _state.value.bt
        if (s.source == BtSource.RADIO) {
            val args = Bundle().apply { putString("addr", dev.addr ?: ""); putString("name", dev.name); putString("password", s.pin ?: "0000") }
            if (s.searching) radioReq("request.bt.stop_find_phone")
            if (radioReq("request.bt.connect", args)) {
                toast("Conectando con ${dev.name}… acepta en el móvil si lo pide" + (s.pin?.let { " (PIN $it)" } ?: ""))
                scope.launch { delay(4000); btQuery() }
                return
            }
        }
        val a = androidAdapter(activity) ?: run { openBtApp(activity); return }
        val d = runCatching { a.getRemoteDevice(dev.addr) }.getOrNull() ?: run { openBtApp(activity); return }
        runCatching { a.cancelDiscovery() }
        if (d.bondState != BluetoothDevice.BOND_BONDED) {
            if (runCatching { d.createBond() }.getOrDefault(false)) toast("Emparejando con ${dev.name}… confirma el código en el móvil")
            else { toast("No se pudo emparejar desde Athoox"); openBtApp(activity) }
            return
        }
        profileCall(activity, d, "connect")
        toast("Conectando con ${dev.name}…")
    }

    @SuppressLint("MissingPermission")
    fun disconnectBt(activity: Context, dev: BtDev) {
        if (_state.value.bt.source == BtSource.RADIO) {
            val args = Bundle().apply { putString("addr", dev.addr ?: ""); putString("name", dev.name) }
            if (radioReq("request.bt.disconnect", args)) { toast("Desconectando ${dev.name}…"); return }
        }
        val d = runCatching { androidAdapter(activity)?.getRemoteDevice(dev.addr) }.getOrNull() ?: return
        profileCall(activity, d, "disconnect")
        toast("Desconectando ${dev.name}…")
    }

    /** Olvidar (desemparejar) [dev]. */
    @SuppressLint("MissingPermission")
    fun forgetBt(activity: Context, dev: BtDev) {
        if (_state.value.bt.source == BtSource.RADIO) {
            val args = Bundle().apply { putString("addr", dev.addr ?: ""); putString("name", dev.name) }
            if (radioReq("request.bt.remove_pair_device", args)) {
                toast("${dev.name} olvidado")
                scope.launch { delay(1500); btQuery() }
                return
            }
        }
        val d = runCatching { androidAdapter(activity)?.getRemoteDevice(dev.addr) }.getOrNull()
        val ok = d != null && runCatching { d.javaClass.getMethod("removeBond").invoke(d) as Boolean }.getOrDefault(false)
        if (ok) toast("${dev.name} olvidado") else { toast("Olvídalo en el Bluetooth de la radio"); openBtApp(activity) }
    }

    /** connect/disconnect de los perfiles de audio y manos libres (métodos ocultos de Android ≤ 11). */
    private fun profileCall(ctx: Context, d: BluetoothDevice, method: String) {
        val a = androidAdapter(ctx) ?: return
        for (p in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) runCatching {
            a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching { proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, d) }
                    main.postDelayed({ runCatching { a.closeProfileProxy(profile, proxy) } }, 3000)
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            }, p)
        }
    }

    fun openBtApp(activity: Context) {
        open(activity, listOf(Intent().setComponent(RADIO_BT_APP), Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
    }

    // ── SIM / datos móviles ──────────────────────────────────────────────────

    private var phoneListener: PhoneStateListener? = null
    @Volatile private var lastSignal: SignalStrength? = null

    @Suppress("DEPRECATION")
    private fun registerCell(ctx: Context) {
        if (phoneListener != null) return
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        val l = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) { lastSignal = signalStrength }
        }
        runCatching { tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS); phoneListener = l }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readCell(ctx: Context, old: CellState, keepHistory: Boolean = true): CellState {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return old.copy(sim = "Sin módem")
        val sim = when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> "Lista"
            TelephonyManager.SIM_STATE_ABSENT -> "Sin SIM"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "Pide el PIN"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Bloqueada (PUK)"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Bloqueada por operador"
            TelephonyManager.SIM_STATE_NOT_READY -> "Arrancando"
            else -> "Desconocida"
        }
        val ready = tm.simState == TelephonyManager.SIM_STATE_READY
        val ss = (if (Build.VERSION.SDK_INT >= 28) runCatching { tm.signalStrength }.getOrNull() else null) ?: lastSignal
        var tech: String? = null
        var dbm: Int? = null; var rsrp: Int? = null; var rsrq: Int? = null; var snr: Int? = null
        var level = 0
        if (ss != null) {
            level = ss.level
            if (Build.VERSION.SDK_INT >= 29) {
                val c = ss.cellSignalStrengths.maxByOrNull { it.level }
                when (c) {
                    is CellSignalStrengthLte -> { tech = "4G"; rsrp = c.rsrp.valid(); rsrq = c.rsrq.valid(); snr = c.rssnr.valid(); dbm = c.dbm.valid() }
                    is CellSignalStrengthNr -> { tech = "5G"; dbm = c.dbm.valid(); rsrp = c.ssRsrp.valid(); rsrq = c.ssRsrq.valid(); snr = c.ssSinr.valid() }
                    is CellSignalStrengthWcdma -> { tech = "3G"; dbm = c.dbm.valid() }
                    is CellSignalStrengthGsm -> { tech = "2G"; dbm = c.dbm.valid() }
                    null -> Unit
                    else -> { dbm = c.dbm.valid() }
                }
                if (c != null) level = c.level
            } else {
                dbm = (ss.gsmSignalStrength.takeIf { it in 0..31 })?.let { -113 + 2 * it }
            }
        }
        val netType = if (has(ctx, Manifest.permission.READ_PHONE_STATE)) runCatching { if (Build.VERSION.SDK_INT >= 24) tm.dataNetworkType else tm.networkType }.getOrNull() else null
        netType?.let { techOf(it) }?.let { tech = it }
        val dataOn = runCatching { tm.isDataEnabled }.getOrNull()
        val hist = if (keepHistory && dbm != null) (old.history + dbm).takeLast(60) else old.history
        return CellState(
            sim = sim, simReady = ready,
            operator = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm.simOperatorName?.takeIf { it.isNotBlank() },
            tech = tech, level = if (ready) level else 0, dbm = dbm, rsrp = rsrp, rsrq = rsrq, snr = snr,
            dataOn = dataOn, roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false), history = hist,
        )
    }

    private fun Int.valid(): Int? = takeIf { it != Int.MAX_VALUE && it != Int.MIN_VALUE }

    private fun techOf(t: Int): String? = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        19 /* LTE_CA */ -> "4G+"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        else -> null
    }

    /** Calidad de la señal 4G en palabras, por RSRP (dBm). */
    fun rsrpWord(rsrp: Int): String = when {
        rsrp >= -85 -> "excelente"
        rsrp >= -100 -> "buena"
        rsrp >= -110 -> "regular"
        else -> "débil"
    }

    fun openMobileData(activity: Context) {
        val panel = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else null
        open(activity, listOfNotNull(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS), panel, Intent(Settings.ACTION_WIRELESS_SETTINGS)))
    }

    // ── GPS ──────────────────────────────────────────────────────────────────

    private var gnssCb: GnssStatus.Callback? = null
    @Volatile private var sats: List<Sat> = emptyList()
    @Volatile private var ttff: Int? = null
    @Volatile private var lastFix: Location? = null

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastFix = location }
        @Deprecated("Android < 10") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    private fun registerGps(ctx: Context) {
        if (gnssCb != null || !has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                sats = (0 until status.satelliteCount).map { i ->
                    Sat(status.getSvid(i), constellation(status.getConstellationType(i)), status.getCn0DbHz(i), status.usedInFix(i))
                }
            }
            override fun onFirstFix(ttffMillis: Int) { ttff = ttffMillis }
            override fun onStopped() { sats = emptyList() }
        }
        runCatching {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(cb, main)
            gnssCb = cb
        }
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper()) }
    }

    private fun constellation(t: Int) = when (t) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        else -> "Otro"
    }

    @SuppressLint("MissingPermission")
    private fun readGps(ctx: Context, old: GpsState): GpsState {
        val perm = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return old
        val enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val loc = lastFix ?: if (perm) runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() else null
        return GpsState(perm, enabled, sats.sortedByDescending { it.cn0 }, loc, ttff)
    }

    fun openLocation(activity: Context) = open(activity, listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))

    fun restartGps(activity: Context) {
        val ctx = activity.applicationContext
        val lm = ctx.getSystemService(LocationManager::class.java)
        runCatching { lm?.removeUpdates(gpsListener) }
        gnssCb?.let { runCatching { lm?.unregisterGnssStatusCallback(it) } }
        gnssCb = null; lastFix = null; sats = emptyList()
        com.cockpitf30.vehicle.telemetry.Telemetry.restartGps(ctx)
        registerGps(ctx)
        toast("GPS reiniciado: puede tardar un minuto en volver a fijar")
    }

    // ── Comunes ──────────────────────────────────────────────────────────────

    /** Abre la primera de [intents] que exista. */
    private fun open(activity: Context, intents: List<Intent>): Boolean {
        for (i in intents) {
            if (activity !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { activity.startActivity(i); true }.getOrDefault(false)) return true
        }
        toast("La radio no tiene esa pantalla de ajustes")
        return false
    }
}
