package com.rudysooo.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/** Pièce jointe prête à être envoyée en MMS (image réduite, ou vidéo courte). */
class Attachment(val bytes: ByteArray, val mime: String, val name: String, val thumb: Bitmap?)

object Attachments {
    private const val MAX_VIDEO = 1_000_000

    fun load(ctx: Context, uri: Uri): Result<Attachment> = runCatching {
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "piece_jointe"
        if (mime.startsWith("image/")) {
            val bmp = decode(ctx, uri, 1280) ?: error("Image illisible")
            val (bytes, finalBmp) = compress(bmp)
            Attachment(bytes, "image/jpeg", name.substringBeforeLast('.') + ".jpg", thumb(finalBmp))
        } else {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Fichier illisible")
            require(bytes.size <= MAX_VIDEO) { "Fichier trop lourd pour un MMS (max 1 Mo)" }
            Attachment(bytes, mime, name, null)
        }
    }

    private fun decode(ctx: Context, uri: Uri, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > max * 2 || bounds.outHeight / sample > max * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    }

    /** Réduit l'image jusqu'à ~300 Ko pour passer chez la plupart des opérateurs. */
    private fun compress(src: Bitmap): Pair<ByteArray, Bitmap> {
        var bmp = src; var q = 80
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= 300_000 || (q <= 50 && maxOf(bmp.width, bmp.height) <= 480)) return out.toByteArray() to bmp
            if (q > 50) q -= 10
            else bmp = Bitmap.createScaledBitmap(bmp, bmp.width * 3 / 4, bmp.height * 3 / 4, true)
        }
    }

    private fun thumb(b: Bitmap): Bitmap {
        val s = 220f / maxOf(b.width, b.height)
        return if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
    }

    /** Décode une pièce jointe d'un MMS reçu, pour l'affichage. */
    private val cache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(uri: Uri, max: Int): Bitmap? = cache.get("$uri@$max")

    fun loadPart(ctx: Context, uri: Uri, max: Int): Bitmap? {
        val key = "$uri@$max"
        cache.get(key)?.let { return it }
        return runCatching { decode(ctx, uri, max) }.getOrNull()?.also { cache.put(key, it) }
    }
}
