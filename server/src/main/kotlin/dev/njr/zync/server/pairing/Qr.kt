package dev.njr.zync.server.pairing

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** Renders a string as a QR code using terminal half-block characters. */
object Qr {
    fun ascii(text: String, quietZone: Int = 2): String {
        val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
        val size = matrix.width
        val total = size + quietZone * 2
        fun dark(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= total || y >= total) return false
            val mx = x - quietZone
            val my = y - quietZone
            if (mx < 0 || my < 0 || mx >= size || my >= size) return false
            return matrix.get(mx, my).toInt() == 1
        }

        val sb = StringBuilder()
        var y = 0
        while (y < total) {
            for (x in 0 until total) {
                val top = dark(x, y)
                val bottom = dark(x, y + 1)
                sb.append(
                    when {
                        top && bottom -> '█' // full block
                        top -> '▀'            // upper half
                        bottom -> '▄'         // lower half
                        else -> ' '
                    },
                )
            }
            sb.append('\n')
            y += 2
        }
        return sb.toString()
    }
}
