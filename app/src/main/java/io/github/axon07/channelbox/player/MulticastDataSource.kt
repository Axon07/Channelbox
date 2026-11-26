package io.github.axon07.channelbox.player

import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.MembershipKey
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/**
 * Receives udp:// and rtp:// streams (IPTV multicast from an ISP, or plain UDP), VLC-style links:
 *
 *   udp://@239.1.1.1:1234            multicast group 239.1.1.1, port 1234
 *   rtp://@239.1.1.1:5000            same, packets wrapped in RTP
 *   udp://10.0.0.5@232.1.1.1:1234    source-specific multicast (IGMPv3), only from 10.0.0.5
 *   udp://@:1234                     anything sent to this box on port 1234
 *
 * RTP headers are removed whatever the link says (some playlists call RTP streams udp://), so the
 * player always gets the plain MPEG-TS inside. A large receive buffer keeps HD streams from
 * dropping packets while the player is busy.
 */
@OptIn(UnstableApi::class)
class MulticastDataSource : BaseDataSource(/* isNetwork = */ true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = MulticastDataSource()
    }

    private interface Receiver {
        /** Fills [buf] with one packet and returns its length; throws [SocketTimeoutException] when nothing comes. */
        fun receive(buf: ByteArray): Int
        fun close()
    }

    private var uri: Uri? = null
    private var receiver: Receiver? = null
    private var opened = false
    private val packet = ByteArray(MAX_PACKET)
    private var pos = 0
    private var limit = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        val port = u.port
        if (port <= 0) throw error(IOException("No port in $u"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        try {
            val host = u.host?.takeIf { it.isNotEmpty() }
            val group = host?.let { InetAddress.getByName(it) }
            val source = u.userInfo?.takeIf { it.isNotEmpty() }?.let { InetAddress.getByName(it) }
            receiver = if (group != null && group.isMulticastAddress && source != null && Build.VERSION.SDK_INT >= 24) {
                SourceSpecific(group, source, port)
            } else {
                Plain(group, source, port)
            }
        } catch (e: SecurityException) {
            throw error(IOException(e), PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        } catch (e: IOException) {
            throw error(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        pos = 0
        limit = 0
        opened = true
        transferStarted(dataSpec)
        return androidx.media3.common.C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val r = receiver ?: throw IOException("Not open")
        while (pos >= limit) {
            val n = try {
                r.receive(packet)
            } catch (e: SocketTimeoutException) {
                throw error(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
            } catch (e: IOException) {
                throw error(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            bytesTransferred(n)
            pos = payloadStart(packet, n)
            limit = payloadEnd(packet, n)
        }
        val count = minOf(length, limit - pos)
        System.arraycopy(packet, pos, buffer, offset, count)
        pos += count
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        receiver?.let { runCatching { it.close() } }
        receiver = null
        pos = 0
        limit = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun error(cause: IOException, code: Int) = DataSourceException(cause, code)

    /** Unicast, or any-source multicast (and the fallback for source-specific on Android 6 and older). */
    private class Plain(group: InetAddress?, private val source: InetAddress?, port: Int) : Receiver {
        private val socket: DatagramSocket
        private val joined: InetAddress?

        init {
            if (group != null && group.isMulticastAddress) {
                val s = MulticastSocket(null as java.net.SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                s.joinGroup(group)
                socket = s
                joined = group
            } else {
                socket = DatagramSocket(null as java.net.SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                joined = null
            }
            socket.soTimeout = TIMEOUT_MS
            runCatching { socket.receiveBufferSize = RECEIVE_BUFFER }
        }

        override fun receive(buf: ByteArray): Int {
            val p = DatagramPacket(buf, buf.size)
            while (true) {
                socket.receive(p)
                // Source-specific link on an old Android: joined for any source, keep only the one asked for.
                if (source == null || p.address == source) return p.length
                p.length = buf.size
            }
        }

        override fun close() {
            if (joined != null) runCatching { (socket as MulticastSocket).leaveGroup(joined) }
            socket.close()
        }
    }

    /** Source-specific multicast (IGMPv3): joins (source, group) on the network the group is reached through. */
    @RequiresApi(24)
    private class SourceSpecific(group: InetAddress, source: InetAddress, port: Int) : Receiver {
        private val family = if (group is Inet4Address) StandardProtocolFamily.INET else StandardProtocolFamily.INET6
        private val channel: DatagramChannel = DatagramChannel.open(family)
        private val selector: Selector = Selector.open()
        private val key: MembershipKey
        private val buffer = ByteBuffer.allocate(MAX_PACKET)

        init {
            try {
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                runCatching { channel.setOption(StandardSocketOptions.SO_RCVBUF, RECEIVE_BUFFER) }
                channel.bind(InetSocketAddress(port))
                key = channel.join(group, networkFor(group), source)
                channel.configureBlocking(false)
                channel.register(selector, SelectionKey.OP_READ)
            } catch (e: Exception) {
                close()
                throw if (e is IOException) e else IOException(e)
            }
        }

        override fun receive(buf: ByteArray): Int {
            while (true) {
                buffer.clear()
                if (channel.receive(buffer) != null) {
                    val n = buffer.position()
                    System.arraycopy(buffer.array(), 0, buf, 0, n)
                    return n
                }
                if (selector.select(TIMEOUT_MS.toLong()) == 0) throw SocketTimeoutException("No data for ${TIMEOUT_MS / 1000} s")
                selector.selectedKeys().clear()
            }
        }

        override fun close() {
            runCatching { key.drop() } // not set yet if joining failed
            runCatching { selector.close() }
            runCatching { channel.close() }
        }

        /** The network the box would send to [group] through (Ethernet or Wi-Fi), else the first that can do multicast. */
        private fun networkFor(group: InetAddress): NetworkInterface {
            runCatching {
                DatagramSocket().use { probe ->
                    probe.connect(group, 9) // UDP connect only picks a route; nothing is sent
                    NetworkInterface.getByInetAddress(probe.localAddress)
                }
            }.getOrNull()?.let { return it }
            return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().firstOrNull {
                it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is Inet4Address }
            } ?: throw IOException("No network that can receive multicast")
        }
    }

    companion object {
        private const val MAX_PACKET = 65_536
        private const val TIMEOUT_MS = 8_000
        private const val RECEIVE_BUFFER = 4 * 1024 * 1024
        private const val TS_SYNC = 0x47

        /** Where the MPEG-TS starts in a packet: after the RTP header if there is one. */
        internal fun payloadStart(p: ByteArray, n: Int): Int {
            if (n <= 0) return 0
            val b0 = p[0].toInt() and 0xFF
            if (b0 == TS_SYNC || b0 and 0xC0 != 0x80 || n < 12) return 0 // plain TS (or unknown): as is
            var off = 12 + 4 * (b0 and 0x0F) // fixed header + CSRC list
            if (b0 and 0x10 != 0) { // header extension
                if (off + 4 > n) return n
                val words = ((p[off + 2].toInt() and 0xFF) shl 8) or (p[off + 3].toInt() and 0xFF)
                off += 4 + 4 * words
            }
            return off.coerceAtMost(n)
        }

        /** End of the payload: drops RTP padding. */
        internal fun payloadEnd(p: ByteArray, n: Int): Int {
            if (n <= 0) return 0
            val b0 = p[0].toInt() and 0xFF
            if (b0 == TS_SYNC || b0 and 0xC0 != 0x80 || b0 and 0x20 == 0 || n < 12) return n
            return (n - (p[n - 1].toInt() and 0xFF)).coerceAtLeast(payloadStart(p, n))
        }
    }
}
