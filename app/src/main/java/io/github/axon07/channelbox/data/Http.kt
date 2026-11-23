package io.github.axon07.channelbox.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** User agent used for every request that doesn't set its own (Settings → Network). */
object UserAgent {
    const val DEFAULT = "Channelbox/1.0 (Linux; Android) ExoPlayer"

    /** Name to user-agent string. Empty string = Channelbox default. */
    val PRESETS = linkedMapOf(
        "Channelbox (default)" to "",
        "VLC" to "VLC/3.0.20 LibVLC/3.0.20",
        "Kodi" to "Kodi/21.0 (Linux; Android 12.0; Build) Android/12.0.0 Sys_CPU/aarch64 App_Bitness/64 Version/21.0",
        "Chrome (Android)" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Smart TV" to "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36",
    )

    @Volatile var current: String = DEFAULT

    fun apply(custom: String) {
        current = custom.ifBlank { DEFAULT }
    }

    fun presetName(value: String): String =
        PRESETS.entries.firstOrNull { it.value == value }?.key ?: "Custom"
}

/** DNS-over-HTTPS choices: name to (URL, bootstrap IPs). Gets around ISP DNS blocking. */
val DNS_PRESETS = linkedMapOf(
    "system" to ("System (your ISP)" to emptyList<String>()),
    "cloudflare" to ("https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")),
    "google" to ("https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")),
    "quad9" to ("https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")),
    "adguard" to ("https://dns.adguard-dns.com/dns-query" to listOf("94.140.14.14", "94.140.15.15")),
    // Control D's free resolvers (no account, no logging).
    "controld" to ("https://freedns.controld.com/p0" to CONTROLD_FREE_IPS),
    "controld_malware" to ("https://freedns.controld.com/p1" to CONTROLD_FREE_IPS),
    "controld_ads" to ("https://freedns.controld.com/p2" to CONTROLD_FREE_IPS),
    "controld_social" to ("https://freedns.controld.com/p3" to CONTROLD_FREE_IPS),
    // Your own Control D profile (its custom rules): https://dns.controld.com/<resolver id>.
    "controld_profile" to ("" to CONTROLD_PROFILE_IPS),
    "custom" to ("" to emptyList()),
)

private val CONTROLD_FREE_IPS get() = listOf("76.76.2.11", "76.76.10.11")
private val CONTROLD_PROFILE_IPS get() = listOf("76.76.2.22", "76.76.10.22")

/**
 * The DNS-over-HTTPS address for [key]: a preset's, your Control D profile's (from its resolver id
 * or full link) or your custom one. Null for "system"/"none" or a missing address.
 */
fun dohAddress(key: String, customUrl: String, controlDId: String): String? = when (key) {
    "system", "none" -> null
    "custom" -> customUrl.trim().takeIf { it.startsWith("https://") }
    "controld_profile" -> controlDId.trim().let { id ->
        when {
            id.isEmpty() -> null
            id.startsWith("https://") -> id
            else -> "https://dns.controld.com/" + id.removePrefix("dns.controld.com/").trim('/')
        }
    }
    else -> DNS_PRESETS[key]?.first?.takeIf { it.startsWith("https://") }
}

val DNS_LABELS = linkedMapOf(
    "system" to "System (your ISP)",
    "cloudflare" to "Cloudflare 1.1.1.1",
    "google" to "Google 8.8.8.8",
    "quad9" to "Quad9",
    "adguard" to "AdGuard",
    "controld" to "Control D – unfiltered",
    "controld_malware" to "Control D – blocks malware",
    "controld_ads" to "Control D – blocks malware & ads",
    "controld_social" to "Control D – blocks malware, ads & social",
    "controld_profile" to "Control D – my own profile",
    "custom" to "Custom",
)

/**
 * The app's one HTTP client (playlists, guides, streams, logos, recordings, updates).
 * Proxy and encrypted DNS from [net] are applied here; changing them needs an app restart.
 */
fun createHttpClient(net: NetworkSettings = NetworkSettings()): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") == null) {
                chain.proceed(req.newBuilder().header("User-Agent", UserAgent.current).build())
            } else {
                chain.proceed(req)
            }
        }

    // Proxy (HTTP or SOCKS5).
    if (net.proxyType != "none" && net.proxyHost.isNotBlank() && net.proxyPort in 1..65535) {
        val type = if (net.proxyType == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP
        builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(net.proxyHost, net.proxyPort)))
        if (net.proxyUser.isNotBlank()) {
            if (type == Proxy.Type.HTTP) {
                val credential = Credentials.basic(net.proxyUser, net.proxyPass)
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            } else {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(net.proxyUser, net.proxyPass.toCharArray())
                })
            }
        }
    }

    // Encrypted DNS (DNS-over-HTTPS): the main one, then the backup one, then the system DNS.
    val chain = listOfNotNull(
        doh(net.dns, dohAddress(net.dns, net.dnsCustomUrl, net.dnsControlDId)),
        doh(net.dnsBackup, dohAddress(net.dnsBackup, net.dnsBackupUrl, net.dnsControlDId)),
    )
    if (chain.isNotEmpty()) {
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                for (dns in chain) {
                    try {
                        return dns.lookup(hostname)
                    } catch (_: Exception) {
                        // Unreachable or no answer: try the next one.
                    }
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        })
    }
    return builder.build()
}

private fun doh(key: String, address: String?): Dns? {
    val url = address?.toHttpUrlOrNull() ?: return null
    val bootstrap = DNS_PRESETS[key]?.second.orEmpty().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    return DnsOverHttps.Builder()
        .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build())
        .url(url)
        .apply { if (bootstrap.isNotEmpty()) bootstrapDnsHosts(bootstrap) }
        .includeIPv6(false)
        .build()
}

/**
 * Downloads [url] and hands the body stream to [block] on an IO thread.
 * Cancelling the coroutine aborts the transfer straight away; [timeoutSeconds] > 0 caps the whole call.
 */
suspend fun <T> OkHttpClient.download(url: String, timeoutSeconds: Long = 0, block: (InputStream) -> T): T =
    withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a valid web address: ${redact(url)}")
        }
        val client = if (timeoutSeconds > 0) newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build() else this@download
        val call = client.newCall(request)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}${httpHint(response.code)}")
                val body = response.body ?: throw IOException("Empty response")
                block(body.byteStream())
            }
        } catch (e: IOException) {
            ensureActive() // cancelled on purpose: report cancellation, not an error
            if (e is java.io.InterruptedIOException) throw IOException("The server took too long to answer")
            throw e
        } finally {
            watcher.cancel()
        }
    }

private fun httpHint(code: Int): String = when (code) {
    401, 403 -> " – the server refused it (often because the account is busy, e.g. a channel is playing on a one-connection account; otherwise check the login)"
    404 -> " – the link wasn't found"
    in 500..599 -> " – the provider's server has a problem"
    else -> ""
}

/** Transparently un-gzips .gz guide files (detected by magic bytes, not file name). */
fun maybeGunzip(input: InputStream): InputStream {
    val buffered = BufferedInputStream(input, 64 * 1024)
    buffered.mark(2)
    val b1 = buffered.read()
    val b2 = buffered.read()
    buffered.reset()
    return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
}

/** Keeps usernames/passwords out of error messages shown on screen. */
fun redact(url: String): String = url
    .replace(Regex("(?i)(password|username)=[^&]*"), "$1=***")
    .substringBefore("/live/")
