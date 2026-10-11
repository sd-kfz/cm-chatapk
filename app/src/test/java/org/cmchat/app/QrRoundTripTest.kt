package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.cmchat.app.crypto.CmId
import org.cmchat.app.crypto.CryptoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test


class QrRoundTripTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))


    private fun drawAndRead(text: String, scale: Int = 6): String {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        val w = m.width * scale
        val h = m.height * scale
        val px = IntArray(w * h) { i ->
            if (m.get((i % w) / scale, (i / w) / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
    }

    @Test
    fun my_id_qr_scans_back_to_the_exact_cmc_id_and_is_accepted() {
        val (pub, _) = crypto.newIdentityKeypair()
        val id = CmId.encode("q".repeat(56) + ".onion", pub)
        val scanned = drawAndRead(id)
        assertEquals(id, scanned)

        val decoded = CmId.decode(scanned.trim())
        assertNotNull(decoded)
        assertEquals(pub.lowercase(), decoded!!.identityPubKeyHex.lowercase())
    }

    @Test
    fun copy_or_share_with_stray_whitespace_still_adds() {

        val (pub, _) = crypto.newIdentityKeypair()
        val id = CmId.encode("r".repeat(56) + ".onion", pub)
        assertNotNull(CmId.decode(" $id\n".trim()))
    }

    @Test
    fun a_qr_that_is_not_a_cmc_id_is_refused() {
        assertNull(CmId.decode(drawAndRead("https://example.com/not-an-id").trim()))
    }
}
