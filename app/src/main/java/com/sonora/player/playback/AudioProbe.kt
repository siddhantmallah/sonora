package com.sonora.player.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.DataInputStream

data class SourceFormat(val sampleRate: Int, val bitDepth: Int, val channels: Int)

/**
 * Reads the real sample rate / bit depth from FLAC and WAV headers, which MediaStore does not expose.
 * This is what powers the "24-bit / 96 kHz · Hi-Res" badge.
 */
object AudioProbe {
    fun probe(context: Context, uri: Uri, mime: String): SourceFormat? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = DataInputStream(raw.buffered())
            val head = ByteArray(4)
            input.readFully(head)
            when {
                head.contentEquals("fLaC".toByteArray()) -> {
                    // First metadata block must be STREAMINFO (34 bytes) after a 4-byte header.
                    input.skipBytes(4 + 10)
                    val b = ByteArray(8)
                    input.readFully(b)
                    val v = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
                    val sampleRate = (v ushr 44).toInt() and 0xFFFFF
                    val channels = ((v ushr 41).toInt() and 0x7) + 1
                    val bps = ((v ushr 36).toInt() and 0x1F) + 1
                    SourceFormat(sampleRate, bps, channels)
                }
                head.contentEquals("RIFF".toByteArray()) -> {
                    input.skipBytes(4)
                    val wave = ByteArray(4); input.readFully(wave)
                    // Walk chunks until "fmt ".
                    repeat(16) {
                        val id = ByteArray(4); input.readFully(id)
                        val size = Integer.reverseBytes(input.readInt())
                        if (id.contentEquals("fmt ".toByteArray())) {
                            input.skipBytes(2)
                            val channels = java.lang.Short.reverseBytes(input.readShort()).toInt()
                            val rate = Integer.reverseBytes(input.readInt())
                            input.skipBytes(6)
                            val bits = java.lang.Short.reverseBytes(input.readShort()).toInt()
                            return@use SourceFormat(rate, bits, channels)
                        }
                        input.skipBytes(size + (size and 1))
                    }
                    null
                }
                else -> null
            }
        }
    }.getOrNull()
}

object Artwork {
    /** Decodes album art at roughly [maxPx] for widgets and colour extraction. */
    fun load(context: Context, uri: Uri?, maxPx: Int): Bitmap? {
        uri ?: return null
        if (uri.scheme == "http" || uri.scheme == "https") {
            return runCatching {
                java.net.URL(uri.toString()).openStream().use { BitmapFactory.decodeStream(it) }?.let { bmp ->
                    if (bmp.width > maxPx * 1.5) Bitmap.createScaledBitmap(bmp, maxPx, maxPx * bmp.height / bmp.width, true) else bmp
                }
            }.getOrNull()
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.let { bmp ->
                if (bmp.width > maxPx * 1.5) Bitmap.createScaledBitmap(bmp, maxPx, maxPx * bmp.height / bmp.width, true) else bmp
            }
        }.getOrNull()
    }
}
