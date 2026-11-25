package com.hwezah.grabber

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Toggle
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.frost
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Kept encrypted: AES-GCM under a key made in the Android Keystore that never leaves it; only the ciphertext is
 * stored, in a prefs file of its own. If it ever stops decrypting it reads as unset.
 */
object Secrets {
    private const val ALIAS = "grabber-secrets"
    private const val IV = 12
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("grabber-secrets", Context.MODE_PRIVATE)
    }

    fun get(name: String): String? = runCatching {
        val blob = android.util.Base64.decode(prefs.getString(name, null) ?: return null, android.util.Base64.NO_WRAP)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, blob, 0, IV)) }
        String(cipher.doFinal(blob, IV, blob.size - IV))
    }.getOrNull()

    /** Null forgets it. */
    fun put(name: String, value: String?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)).apply()
    }

    private fun key(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }
}

enum class VpnState { OFF, CONNECTING, ON, FAILED }

/**
 * A WireGuard VPN inside Grabber: a config file from Mullvad, Proton VPN or any WireGuard provider, imported once and
 * kept encrypted in [Secrets]. By default only Grabber's traffic (downloads, the Browser, video sites) goes through it;
 * off, the whole phone does. WireGuard's own library (GoBackend) runs the tunnel; Android asks once for leave.
 */
object Vpn {
    private const val CONFIG = "wireguardConfig"
    private lateinit var app: Context
    private lateinit var sp: SharedPreferences
    private var backend: GoBackend? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var state by mutableStateOf(VpnState.OFF)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped when the config changes, so the screens read it again. */
    var version by mutableStateOf(0)
        private set

    private val tunnel = object : Tunnel {
        override fun getName() = "grabber"
        override fun onStateChange(newState: Tunnel.State) {
            state = if (newState == Tunnel.State.UP) VpnState.ON else VpnState.OFF
        }
    }

    /** At launch: a VPN left on comes back on, if Android still allows it (no prompt needed). */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Secrets.init(app)
        sp = app.getSharedPreferences("vpn", Context.MODE_PRIVATE)
        if (sp.getBoolean("on", false) && hasConfig() && VpnService.prepare(app) == null) connect()
    }

    fun hasConfig() = !Secrets.get(CONFIG).isNullOrBlank()

    fun configText(): String? = Secrets.get(CONFIG)?.takeIf { it.isNotBlank() }

    /** The server the config connects to, to show. */
    fun server(): String? = Secrets.get(CONFIG)?.let(::wireguardServer)

    var onlyApp: Boolean
        get() = sp.getBoolean("onlyApp", true)
        set(v) {
            sp.edit().putBoolean("onlyApp", v).apply()
            if (state == VpnState.ON) connect()
        }

    /** A config file's text, checked before it's kept. Null when it's fine, else why not. */
    fun import(raw: String): String? {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trim() }.trim()
        if (text.isEmpty()) return "That file was empty or couldn't be opened. Pick the .conf again, or paste the config."
        if (!text.contains("[Interface]", true) || !text.contains("PrivateKey", true)) return "That isn't a WireGuard config: it has no [Interface] with a PrivateKey."
        val err = runCatching { Config.parse(text.byteInputStream()) }.exceptionOrNull()
        if (err != null) {
            val why = (err as? com.wireguard.config.BadConfigException)?.let { e -> "${e.reason.name.lowercase().replace('_', ' ')} in ${e.section.name.lowercase()} ${e.location.name.lowercase().replace('_', ' ')}" + (e.text?.let { t -> " (\"$t\")" } ?: "") }
            return "That config couldn't be read: ${why ?: err.message ?: err.javaClass.simpleName}"
        }
        Secrets.put(CONFIG, text)
        version++
        if (state == VpnState.ON) connect()
        return null
    }

    fun forget() {
        disconnect()
        Secrets.put(CONFIG, null)
        version++
    }

    /** Android's "allow this VPN" screen when it hasn't been allowed yet; null when it has. */
    fun permission(): Intent? = VpnService.prepare(app)

    fun connect() {
        val text = Secrets.get(CONFIG) ?: return
        state = VpnState.CONNECTING
        error = null
        sp.edit().putBoolean("on", true).apply()
        scope.launch {
            runCatching {
                val b = backend ?: GoBackend(app).also { backend = it }
                val config = Config.parse(wireguardForApp(text, app.packageName, onlyApp).byteInputStream())
                // From the top: a change of mode or config takes effect.
                runCatching { b.setState(tunnel, Tunnel.State.DOWN, null) }
                b.setState(tunnel, Tunnel.State.UP, config)
            }.onSuccess { state = VpnState.ON }.onFailure {
                state = VpnState.FAILED
                error = "Couldn't connect: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    fun disconnect() {
        sp.edit().putBoolean("on", false).apply()
        scope.launch {
            runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
            state = VpnState.OFF
        }
    }
}

/**
 * A WireGuard config made for Grabber: any app list taken out of [Interface], and with [onlyApp]
 * `IncludedApplications = pkg`, so only Grabber's traffic goes through. A peer without a keep-alive gets one every
 * 25 s, so mobile networks don't drop a quiet tunnel.
 */
fun wireguardForApp(conf: String, pkg: String, onlyApp: Boolean): String {
    val out = StringBuilder()
    var section = ""
    var added = false
    val keepalive = conf.lineSequence().any { it.trim().substringBefore('=').trim().equals("PersistentKeepalive", true) }
    conf.lineSequence().forEach { line ->
        val t = line.trim()
        if (t.startsWith("[") && t.endsWith("]")) {
            if (section.equals("[Interface]", true) && onlyApp && !added) { out.append("IncludedApplications = $pkg\n"); added = true }
            section = t
        }
        if (!keepalive && section.equals("[Peer]", true) && t.substringBefore('=').trim().equals("Endpoint", true)) {
            out.append(line).append('\n').append("PersistentKeepalive = 25\n")
            return@forEach
        }
        val key = t.substringBefore('=').trim()
        if (section.equals("[Interface]", true) && (key.equals("IncludedApplications", true) || key.equals("ExcludedApplications", true))) return@forEach
        out.append(line).append('\n')
    }
    if (section.equals("[Interface]", true) && onlyApp && !added) out.append("IncludedApplications = $pkg\n")
    return out.toString().trimEnd() + "\n"
}

/** The server a config connects to, from its first Endpoint. */
fun wireguardServer(conf: String): String? =
    conf.lineSequence().map { it.trim() }.firstOrNull { it.substringBefore('=').trim().equals("Endpoint", true) }
        ?.substringAfter('=')?.trim()?.let { e -> if (e.startsWith("[")) e.substringAfter('[').substringBefore(']') else e.substringBeforeLast(':') }
        ?.takeIf { it.isNotBlank() }

/** The VPN's on/off: asks Android for leave the first time, then connects. Without a config, [onNoConfig]. */
@Composable
fun VpnSwitch(label: String = "VPN", onNoConfig: () -> Unit = {}) {
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) Vpn.connect()
    }
    val on = Vpn.state == VpnState.ON || Vpn.state == VpnState.CONNECTING
    Row(verticalAlignment = Alignment.CenterVertically) {
        val text = if (Vpn.state == VpnState.CONNECTING) "Connecting…" else label
        if (text.isNotEmpty()) {
            Text(text, style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(8.dp))
        }
        Toggle(on, { want ->
            when {
                !want -> Vpn.disconnect()
                !Vpn.hasConfig() -> onNoConfig()
                else -> Vpn.permission()?.let { allow.launch(it) } ?: Vpn.connect()
            }
        })
    }
}

/** Settings › VPN: the WireGuard config, whether only Grabber goes through it, the switch, and where it connects. */
@Composable
fun VpnSettings() {
    val context = LocalContext.current
    fun say(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()
    val has = remember(Vpn.version) { Vpn.hasConfig() }
    val server = remember(Vpn.version) { Vpn.server() }
    var onlyApp by remember { mutableStateOf(Vpn.onlyApp) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
        say(Vpn.import(text) ?: "VPN config added")
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (Vpn.state) {
                        VpnState.ON -> "Connected"
                        VpnState.CONNECTING -> "Connecting…"
                        VpnState.FAILED -> "Not connected"
                        VpnState.OFF -> if (has) "Off" else "No config yet"
                    },
                    style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = if (Vpn.state == VpnState.ON) Ink.green else Ink.text),
                )
                server?.let { Text("Server: $it", style = Type.meta) }
            }
            if (has) VpnSwitch("")
        }
        Vpn.error?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.red)) }
        if (has) Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Only Grabber", style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                Text("Downloads, the Browser and video sites go through it; the rest of the phone doesn't. Off: the whole phone.", style = Type.meta)
            }
            Toggle(onlyApp, { onlyApp = it; Vpn.onlyApp = it })
        }
        Button(if (has) "Replace config file" else "Import config file", { pick.launch(arrayOf("*/*")) }, Modifier.padding(top = 16.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_upload, compact = true)
        var pasting by remember { mutableStateOf(false) }
        var pasted by remember { mutableStateOf("") }
        if (!pasting) Button("Paste config text", { pasting = true }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_copy, compact = true)
        else {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 140.dp).frost(RoundedCornerShape(9.dp)).padding(12.dp)) {
                if (pasted.isEmpty()) Text("Paste the config here ([Interface] … [Peer] …)", style = Type.meta.copy(color = Ink.faint))
                BasicTextField(
                    pasted, { pasted = it }, Modifier.fillMaxWidth().heightIn(min = 116.dp).focusRequester(focus),
                    textStyle = Type.meta.copy(color = Ink.text), cursorBrush = SolidColor(Ink.green),
                )
            }
            Button("Paste", {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { pasted = it }
            }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_clipboard_paste, compact = true)
            Button("Use this config", {
                val problem = Vpn.import(pasted)
                if (problem == null) { pasting = false; pasted = "" }
                say(problem ?: "VPN config added")
            }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_check, accent = Ink.green, compact = true)
        }
        if (has) Button("Copy config", {
            Vpn.configText()?.let { text ->
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("WireGuard config", text))
                say("Config copied: it holds your private key, keep it safe")
            }
        }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_copy, compact = true)
        if (has) Button("Remove config", { Vpn.forget(); say("VPN config removed") }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_trash_2, compact = true)
        Text(
            "Use a WireGuard config (.conf) from your VPN's website: Mullvad (Account › WireGuard configuration), " +
                "Proton VPN (Downloads › WireGuard configuration), Windscribe or IVPN. Downloading one in Grabber's Browser adds it too. It stays encrypted on this phone.",
            Modifier.padding(top = 12.dp), style = Type.meta.copy(color = Ink.faint),
        )
    }
}
