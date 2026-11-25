package com.hwezah.grabber

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread

/**
 * The Browser's big blocklist, as WatchHub's: StevenBlack's unified hosts and AdGuard's DNS filter (together about
 * 300,000 ad, tracker, malware and pop-under sites), fetched once a week and kept on the phone, read into memory at
 * launch. A request is checked against it (and its parent domains) before it leaves.
 */
object AdBlock {
    private val LISTS = listOf(
        "blocklist.txt" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "blocklist-adguard.txt" to "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/gh-pages/Filters/filter.txt",
    )
    private const val WEEK = 7L * 24 * 3600 * 1000
    private var dir: File? = null

    @Volatile private var hosts: Set<String> = emptySet()

    /** How many sites the lists hold. */
    var count by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (dir != null) return
        val d = context.applicationContext.filesDir
        dir = d
        thread(name = "grabber-adblock") {
            runCatching { load(d) }
            val stale = LISTS.any { (name, _) -> File(d, name).let { !it.exists() || System.currentTimeMillis() - it.lastModified() > WEEK } }
            if (stale) runCatching { update() }
        }
    }

    private fun load(d: File) {
        val all = HashSet<String>(320_000)
        LISTS.forEach { (name, _) -> File(d, name).takeIf { it.exists() }?.let { all += parseHostsList(it.readText()) } }
        hosts = all
        android.os.Handler(android.os.Looper.getMainLooper()).post { count = all.size }
    }

    /** Fetched afresh (weekly by itself). A list that fails keeps its last good copy. Blocking. */
    fun update() {
        val d = dir ?: return
        LISTS.forEach { (name, url) ->
            runCatching {
                val text = com.hwezah.grabber.engine.Http.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("${r.code}")
                    r.body?.string().orEmpty()
                }
                if (parseHostsList(text).size < 1000) error("empty")
                File(d, name).writeText(text)
            }
        }
        load(d)
    }

    fun blocks(host: String): Boolean = hosts.isNotEmpty() && hostBlocked(host, hosts)

    /** A hosts file ("0.0.0.0 ads.example.com") or an AdGuard list ("||ads.example.com^"), as plain host names. */
    fun parseHostsList(text: String): Set<String> {
        val out = HashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("!")) return@forEach
            val host = when {
                line.startsWith("||") -> line.removePrefix("||").substringBefore('^').substringBefore('$').takeIf { '/' !in it && '*' !in it }
                else -> line.split(Regex("\\s+")).let { p -> if (p.size >= 2 && (p[0] == "0.0.0.0" || p[0] == "127.0.0.1")) p[1] else null }
            }?.lowercase()?.trim('.')
            if (host != null && host.contains('.') && host != "localhost" && host != "0.0.0.0") out += host
        }
        return out
    }

    /** [host] or any parent domain of it in [blocked]. */
    fun hostBlocked(host: String, blocked: Set<String>): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in blocked) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Ad boxes a page still lays out once their contents are blocked, hidden by style (specific names only). */
    const val HIDE_SCRIPT = """
(function(){if(document.getElementById('gr-adhide'))return;var s=document.createElement('style');s.id='gr-adhide';
s.textContent='ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[data-ad-slot],[data-ad-client],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],'+
'iframe[id^="google_ads_iframe"],.adsbox,.ad-container,.ad-wrapper,.ad-banner,.ad-slot,.ad-unit,.advertisement,.advert,.sponsored-ad,'+
'[class*="AdSlot"],[id*="ad-banner"],[id*="adbanner"],[class*="sticky-ad"],[class*="adsense"],[aria-label="Advertisement"],'+
'div[id^="taboola"],div[class*="taboola"],div[id^="outbrain"],div[class*="OUTBRAIN"],.mgbox,[id^="mgid"],.popunder,.ad-overlay'+
'{display:none!important;visibility:hidden!important;height:0!important;}';
(document.head||document.documentElement).appendChild(s);})();
"""

    /**
     * Run in every page before its own scripts: YouTube's ad slots taken out of the player's data before the player
     * reads it, and in any player a Skip button pressed as it appears and an ad video muted and sent to its end.
     */
    const val SHIELD_SCRIPT = """
(function(){if(window.__grShield)return;window.__grShield=1;
var AD=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams','adBreakParams'];
function strip(o){try{if(o&&typeof o==='object'){AD.forEach(function(k){if(k in o)delete o[k]});if(o.playerResponse)strip(o.playerResponse);}}catch(e){}return o;}
var jp=JSON.parse;JSON.parse=function(){return strip(jp.apply(this,arguments));};
try{var keep;Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,get:function(){return keep},set:function(v){keep=strip(v)}});}catch(e){}
if(window.Response&&Response.prototype.json){var rj=Response.prototype.json;Response.prototype.json=function(){return rj.apply(this,arguments).then(strip);};}
var SKIP='.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.videoAdUiSkipButton,.jw-skip,.jw-skiptext,.vjs-ad-skip,'+
'[class*="skip-ad"],[class*="skipAd"],[class*="ad-skip"],[id*="skip-ad"],[id*="skipAd"],button[aria-label^="Skip"]';
var ADVID='.ad-showing video,.ad-interrupting video,.vjs-ad-playing video,.jw-flag-ads video,.ima-ad-container video,'+
'[class*="preroll"] video,[id*="preroll"] video,[class*="ad-player"] video';
setInterval(function(){try{
document.querySelectorAll(SKIP).forEach(function(b){try{b.click()}catch(e){}});
document.querySelectorAll(ADVID).forEach(function(v){v.muted=true;if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.2)v.currentTime=v.duration-0.1;v.playbackRate=16;});
}catch(e){}},300);
})();
"""
}
