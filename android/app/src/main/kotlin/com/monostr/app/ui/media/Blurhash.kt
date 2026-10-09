package com.monostr.app.ui.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.withSign

/** Blurhash (woltapp/blurhash): [decode] turns a hash into ARGB pixels as a placeholder while a picture loads (spec 3.2), [encode] makes the hash of a picture about to be uploaded (Plan 10e). */
object Blurhash {
    // Base83 alphabet, fixed by the blurhash spec; not prose.
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    fun decode(hash: String, width: Int, height: Int, punch: Float = 1f): IntArray? {
        if (hash.length < 6 || width <= 0 || height <= 0) return null
        val size = decode83(hash.substring(0, 1)) ?: return null
        val numY = size / 9 + 1
        val numX = size % 9 + 1
        if (hash.length != 4 + 2 * numX * numY) return null
        val quantMax = decode83(hash.substring(1, 2)) ?: return null
        val maxValue = (quantMax + 1) / 166f
        val colors = Array(numX * numY) { FloatArray(3) }
        for (i in colors.indices) {
            if (i == 0) {
                val v = decode83(hash.substring(2, 6)) ?: return null
                colors[i] = floatArrayOf(sRgbToLinear(v shr 16), sRgbToLinear((v shr 8) and 255), sRgbToLinear(v and 255))
            } else {
                val v = decode83(hash.substring(4 + i * 2, 6 + i * 2)) ?: return null
                colors[i] = floatArrayOf(
                    signPow((v / (19 * 19) - 9) / 9f, 2f) * maxValue * punch,
                    signPow(((v / 19) % 19 - 9) / 9f, 2f) * maxValue * punch,
                    signPow((v % 19 - 9) / 9f, 2f) * maxValue * punch,
                )
            }
        }
        val out = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0f; var g = 0f; var b = 0f
            for (j in 0 until numY) for (i in 0 until numX) {
                val basis = (cos(PI * x * i / width) * cos(PI * y * j / height)).toFloat()
                val c = colors[i + j * numX]
                r += c[0] * basis; g += c[1] * basis; b += c[2] * basis
            }
            out[x + y * width] = (0xFF shl 24) or (linearToSRgb(r) shl 16) or (linearToSRgb(g) shl 8) or linearToSRgb(b)
        }
        return out
    }

    /**
     * Encodes ARGB [pixels] (row by row, [width] x [height]) with [componentsX] x [componentsY]
     * components. Null for impossible sizes. Computed in doubles, like the reference implementation.
     */
    fun encode(pixels: IntArray, width: Int, height: Int, componentsX: Int = 4, componentsY: Int = 3): String? {
        if (width <= 0 || height <= 0 || pixels.size != width * height || componentsX !in 1..9 || componentsY !in 1..9) return null
        val factors = Array(componentsX * componentsY) { DoubleArray(3) }
        for (j in 0 until componentsY) for (i in 0 until componentsX) {
            val norm = if (i == 0 && j == 0) 1.0 else 2.0
            var r = 0.0; var g = 0.0; var b = 0.0
            for (y in 0 until height) for (x in 0 until width) {
                val basis = norm * cos(PI * i * x / width) * cos(PI * j * y / height)
                val p = pixels[x + y * width]
                r += basis * sRgbToLinearD((p shr 16) and 255)
                g += basis * sRgbToLinearD((p shr 8) and 255)
                b += basis * sRgbToLinearD(p and 255)
            }
            val scale = 1.0 / (width * height)
            factors[i + j * componentsX] = doubleArrayOf(r * scale, g * scale, b * scale)
        }
        val dc = factors[0]
        val ac = factors.drop(1)
        val out = StringBuilder()
        out.append(encode83((componentsX - 1) + (componentsY - 1) * 9, 1))
        val maxValue: Double
        if (ac.isNotEmpty()) {
            val actualMax = ac.maxOf { c -> max(abs(c[0]), max(abs(c[1]), abs(c[2]))) }
            val quantised = floor(actualMax * 166 - 0.5).toInt().coerceIn(0, 82)
            maxValue = (quantised + 1) / 166.0
            out.append(encode83(quantised, 1))
        } else {
            maxValue = 1.0
            out.append(encode83(0, 1))
        }
        out.append(encode83((linearToSRgbD(dc[0]) shl 16) or (linearToSRgbD(dc[1]) shl 8) or linearToSRgbD(dc[2]), 4))
        for (c in ac) {
            fun quant(v: Double): Int = floor(abs(v / maxValue).pow(0.5).withSign(v) * 9 + 9.5).toInt().coerceIn(0, 18)
            out.append(encode83(quant(c[0]) * 19 * 19 + quant(c[1]) * 19 + quant(c[2]), 2))
        }
        return out.toString()
    }

    private fun encode83(value: Int, length: Int): String {
        val chars = CharArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            chars[i] = ALPHABET[v % 83]
            v /= 83
        }
        return String(chars)
    }

    private fun sRgbToLinearD(v: Int): Double {
        val f = v / 255.0
        return if (f <= 0.04045) f / 12.92 else ((f + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSRgbD(v: Double): Int {
        val c = v.coerceIn(0.0, 1.0)
        val f = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
        return (f * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun decode83(s: String): Int? {
        var v = 0
        for (ch in s) {
            val d = ALPHABET.indexOf(ch)
            if (d < 0) return null
            v = v * 83 + d
        }
        return v
    }

    private fun sRgbToLinear(v: Int): Float {
        val f = v / 255f
        return if (f <= 0.04045f) f / 12.92f else ((f + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSRgb(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        val f = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f
        return (f * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun signPow(v: Float, e: Float): Float = abs(v).pow(e).withSign(v)
}
