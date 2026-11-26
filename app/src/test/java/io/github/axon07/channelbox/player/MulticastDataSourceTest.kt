package io.github.axon07.channelbox.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MulticastDataSourceTest {

    private fun ts(n: Int) = ByteArray(n) { if (it % 188 == 0) 0x47 else 0 }

    @Test
    fun plainTsIsUntouched() {
        val p = ts(1316)
        assertEquals(0, MulticastDataSource.payloadStart(p, p.size))
        assertEquals(1316, MulticastDataSource.payloadEnd(p, p.size))
    }

    @Test
    fun stripsRtpHeader() {
        val p = ByteArray(12) + ts(1316)
        p[0] = 0x80.toByte() // version 2
        p[1] = 33 // MP2T
        assertEquals(12, MulticastDataSource.payloadStart(p, p.size))
        assertEquals(p.size, MulticastDataSource.payloadEnd(p, p.size))
    }

    @Test
    fun stripsCsrcExtensionAndPadding() {
        // 2 CSRCs, a 1-word extension, 4 bytes of padding.
        val header = ByteArray(12 + 8 + 4 + 4)
        header[0] = (0x80 or 0x20 or 0x10 or 0x02).toByte()
        header[12 + 8 + 3] = 1
        val p = header + ts(188) + byteArrayOf(0, 0, 0, 4)
        assertEquals(28, MulticastDataSource.payloadStart(p, p.size))
        assertEquals(28 + 188, MulticastDataSource.payloadEnd(p, p.size))
    }
}
