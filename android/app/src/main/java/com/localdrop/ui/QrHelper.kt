package com.localdrop.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

data class ParsedWifiQr(val ssid: String, val password: String)

object QrHelper {
    fun generate(content: String, sizePx: Int = 520): Bitmap? {
        if (content.isBlank()) return null
        return runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        }.getOrNull()
    }

    /** Lit le format standard `WIFI:T:WPA;S:...;P:...;;` même si le SSID/MDP contient des caractères spéciaux. */
    fun parseWifiQr(raw: String): ParsedWifiQr? {
        if (!raw.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = splitEscaped(raw.substring(5), ';')
        var ssid = ""
        var pass = ""
        for (f in fields) {
            if (f.startsWith("S:", ignoreCase = true)) ssid = unescape(f.substring(2))
            else if (f.startsWith("P:", ignoreCase = true)) pass = unescape(f.substring(2))
        }
        return if (ssid.isNotBlank()) ParsedWifiQr(ssid, pass) else null
    }

    private fun splitEscaped(input: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var esc = false
        for (ch in input) {
            if (esc) {
                cur.append('\\').append(ch)
                esc = false
            } else if (ch == '\\') {
                esc = true
            } else if (ch == sep) {
                out += cur.toString()
                cur.clear()
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var esc = false
        for (ch in s) {
            if (esc) {
                out.append(ch)
                esc = false
            } else if (ch == '\\') {
                esc = true
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }
}
