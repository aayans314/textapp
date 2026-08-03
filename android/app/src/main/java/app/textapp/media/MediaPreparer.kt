package app.textapp.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

data class PreparedMedia(val file: File, val mime: String, val name: String, val w: Int, val h: Int)
data class PreparedThumb(val file: File, val w: Int, val h: Int)

/**
 * Aggressive client-side compression so the server stays a dumb, tiny store:
 * images -> max 1920px JPEG ~q78, videos -> max 1280px H.264 @ 1.4 Mbps via Media3 Transformer.
 */
class MediaPreparer(private val context: Context) {
    private val cacheDir get() = context.cacheDir

    suspend fun prepare(uri: Uri, isVideo: Boolean, onProgress: (Float) -> Unit = {}): PreparedMedia =
        if (isVideo) prepareVideo(uri, onProgress) else prepareImage(uri)

    suspend fun prepareImage(uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
        val name = nameOf(uri)
        val out = File(cacheDir, "img_${System.currentTimeMillis()}.jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_DIM || bounds.outHeight / (sample * 2) >= MAX_IMAGE_DIM) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("cannot decode image")
        var scaled = bmp
        if (bmp.width > MAX_IMAGE_DIM || bmp.height > MAX_IMAGE_DIM) {
            val scale = min(1f, MAX_IMAGE_DIM.toFloat() / max(bmp.width, bmp.height).toFloat())
            val w = (bmp.width * scale).toInt()
            val h = (bmp.height * scale).toInt()
            scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            if (scaled !== bmp) bmp.recycle()
        }
        val w = scaled.width
        val h = scaled.height
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 78, it) }
        scaled.recycle()
        PreparedMedia(out, "image/jpeg", name, w, h)
    }

    suspend fun prepareVideo(uri: Uri, onProgress: (Float) -> Unit): PreparedMedia = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        retriever.release()
        val out = File(cacheDir, "vid_${System.currentTimeMillis()}.mp4")
        val scale = if (w <= 0 || h <= 0) 1f else min(1f, MAX_VIDEO_DIM.toFloat() / max(w, h).toFloat())
        val scaledW = (w * scale).toInt().coerceAtLeast(1)
        val scaledH = (h * scale).toInt().coerceAtLeast(1)
        val transformation = TransformationRequest.Builder()
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(VIDEO_BITRATE)
                    .setiFrameIntervalSeconds(1f)
                    .build(),
            )
            .setEnableFallback(true)
            .build()
        val done = CompletableDeferred<Result<Unit>>()
        val transformer = Transformer.Builder(context)
            .setTransformationRequest(transformation)
            .setVideoEffects(listOf(ScaleAndRotateTransformation.Builder().setScale(scale, scale).build()))
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Result.success(Unit))
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exception: ExportException) {
                    done.complete(Result.failure(exception))
                }
            })
            .build()
        transformer.start(MediaItem.fromUri(uri), out.absolutePath)
        done.await().getOrThrow()
        if (!out.exists() || out.length() == 0L) throw IOException("video compression produced no output")
        PreparedMedia(out, "video/mp4", nameOf(uri), scaledW, scaledH)
    }

    suspend fun prepareThumb(file: File, isVideo: Boolean): PreparedThumb = withContext(Dispatchers.IO) {
        val out = File(cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
        var bmp: Bitmap? = null
        if (isVideo) {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            bmp = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_THUMB_DIM || bounds.outHeight / (sample * 2) >= MAX_THUMB_DIM) {
                sample *= 2
            }
            bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        val src = bmp ?: throw IOException("cannot extract thumbnail")
        val scale = min(1f, MAX_THUMB_DIM.toFloat() / max(src.width, src.height).toFloat())
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
        if (scaled !== src) src.recycle()
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        scaled.recycle()
        PreparedThumb(out, w, h)
    }

    private fun nameOf(uri: Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
        }
        return name ?: uri.lastPathSegment ?: "file"
    }

    companion object {
        private const val MAX_IMAGE_DIM = 1920
        private const val MAX_THUMB_DIM = 360
        private const val MAX_VIDEO_DIM = 1280
        private const val VIDEO_BITRATE = 1_400_000
    }
}
