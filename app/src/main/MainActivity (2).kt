package com.example.videoenhancer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

const val MAX_SECONDS = 120
const val AUDIO_BPS = 192_000

val FPS_OPTS = listOf(0, 24, 30, 60, 90, 120)
val RES_OPTS = listOf(0, 720, 1080, 1440, 2160)

data class VideoInfo(
    val width: Int,
    val height: Int,
    val seconds: Double,
    val sizeBytes: Long,
    val fps: Double = 0.0,
    val codec: String = "",
    val bitrateBps: Long = 0L,
    val name: String = "",
    val folder: String = ""
)

var activeTransformer: Transformer? = null

object VideoState {
    var uri by mutableStateOf<Uri?>(null)
    var info by mutableStateOf<VideoInfo?>(null)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var fps by mutableStateOf(0)
    var resHeight by mutableStateOf(0)
    var quality by mutableStateOf(1)
    var h265 by mutableStateOf(true)
    var platform by mutableStateOf(0)
    var advice by mutableStateOf("")
    var bright by mutableStateOf(0f)
    var contrast by mutableStateOf(0f)
    var sat by mutableStateOf(0f)
    var warmth by mutableStateOf(0f)
    var currentOut: File? = null
    val handler = Handler(Looper.getMainLooper())
}

object ImgState {
    var src by mutableStateOf<Bitmap?>(null)
    var preview by mutableStateOf<Bitmap?>(null)
    var scale by mutableStateOf(2)
    var sharpen by mutableStateOf(0.5f)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
}

// ---------------------------------------------------------------- video info

fun readSize(context: Context, uri: Uri): Long {
    var size = 0L
    try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) {
                    size = c.getLong(idx)
                }
            }
        }
    } catch (e: Exception) {
    }
    if (size <= 0L) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                size = it.statSize
            }
        } catch (e: Exception) {
        }
    }
    return if (size > 0L) size else 0L
}

fun pqReadName(context: Context, uri: Uri): String {
    var name = ""
    try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && !c.isNull(idx)) name = c.getString(idx)
            }
        }
    } catch (e: Exception) {
    }
    return name
}

fun pqReadFolder(context: Context, uri: Uri): String {
    var folder = ""
    if (Build.VERSION.SDK_INT < 29) return folder
    try {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (idx >= 0 && !c.isNull(idx)) folder = c.getString(idx)
            }
        }
    } catch (e: Exception) {
    }
    return folder
}

fun pqReadTrackFps(context: Context, uri: Uri): Double {
    val ex = MediaExtractor()
    var fps = 0.0
    try {
        ex.setDataSource(context, uri, null)
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                fps = try {
                    f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                } catch (e: Exception) {
                    try {
                        f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                    } catch (e2: Exception) {
                        0.0
                    }
                }
                break
            }
        }
    } catch (e: Exception) {
    } finally {
        ex.release()
    }
    return fps
}

fun readInfo(context: Context, uri: Uri): VideoInfo? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        var w = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 0
        var h = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
        )?.toIntOrNull() ?: 0
        val ms = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        val bitrate = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_BITRATE
        )?.toLongOrNull() ?: 0L
        val mime = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_MIMETYPE
        ) ?: ""
        if (rot == 90 || rot == 270) {
            val t = w
            w = h
            h = t
        }
        val seconds = ms / 1000.0
        var fps = pqReadTrackFps(context, uri)
        if (fps <= 0.0 && Build.VERSION.SDK_INT >= 28 && seconds > 0.0) {
            val frames = r.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
            )?.toLongOrNull() ?: 0L
            if (frames > 0L) fps = frames / seconds
        }
        VideoInfo(
            w, h, seconds, readSize(context, uri),
            fps, mime, bitrate,
            pqReadName(context, uri), pqReadFolder(context, uri)
        )
    } catch (e: Exception) {
        null
    } finally {
        r.release()
    }
}

// ---------------------------------------------------------------- gallery

fun saveToGallery(context: Context, file: File, prefix: String): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val values = ContentValues().apply {
        put(
            MediaStore.Video.Media.DISPLAY_NAME,
            "${prefix}_${System.currentTimeMillis()}.mp4"
        )
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(
            MediaStore.Video.Media.RELATIVE_PATH,
            Environment.DIRECTORY_MOVIES + "/PrimeQuality"
        )
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val target = resolver.insert(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
    ) ?: return false
    return try {
        val out = resolver.openOutputStream(target)
        if (out == null) {
            resolver.delete(target, null, null)
            false
        } else {
            out.use { o ->
                file.inputStream().use { it.copyTo(o) }
            }
            val done = ContentValues()
            done.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(target, done, null, null)
            true
        }
    } catch (e: Exception) {
        try {
            resolver.delete(target, null, null)
        } catch (e2: Exception) {
        }
        false
    }
}

fun pqSaveBitmap(context: Context, bmp: Bitmap): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val values = ContentValues().apply {
        put(
            MediaStore.Images.Media.DISPLAY_NAME,
            "prime_${System.currentTimeMillis()}.jpg"
        )
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/PrimeQuality"
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val target = resolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
    ) ?: return false
    return try {
        val out = resolver.openOutputStream(target)
        if (out == null) {
            resolver.delete(target, null, null)
            false
        } else {
            out.use { o ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, o)
            }
            val done = ContentValues()
            done.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(target, done, null, null)
            true
        }
    } catch (e: Exception) {
        try {
            resolver.delete(target, null, null)
        } catch (e2: Exception) {
        }
        false
    }
}

// ---------------------------------------------------------------- video settings math

fun formatName(mime: String?): String {
    if (mime == null || mime.isEmpty()) return "naməlum"
    if (mime == "video/hevc") return "H.265 (HEVC)"
    if (mime == "video/avc") return "H.264 (AVC)"
    return mime
}

fun pqMime(): String {
    return if (VideoState.h265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
}

val pqCapsCache = HashMap<String, MediaCodecInfo.VideoCapabilities?>()

fun pqVideoCaps(mime: String): MediaCodecInfo.VideoCapabilities? {
    if (pqCapsCache.containsKey(mime)) return pqCapsCache[mime]
    var best: MediaCodecInfo.VideoCapabilities? = null
    try {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (ci in list.codecInfos) {
            if (!ci.isEncoder) continue
            if (!ci.supportedTypes.any { it.equals(mime, true) }) continue
            val caps = ci.getCapabilitiesForType(mime).videoCapabilities ?: continue
            val hw = Build.VERSION.SDK_INT >= 29 && ci.isHardwareAccelerated
            if (best == null || hw) best = caps
            if (hw) break
        }
    } catch (e: Exception) {
    }
    pqCapsCache[mime] = best
    return best
}

// Seçim qısa tərəfə görə sayılır (1080p = 1080x1920 şaquli video da daxil)
fun pqDimsForSide(info: VideoInfo, side: Int): Pair<Int, Int> {
    val w: Int
    val h: Int
    if (info.width < info.height) {
        w = side
        h = (side.toDouble() * info.height / info.width).toInt()
    } else {
        h = side
        w = (side.toDouble() * info.width / info.height).toInt()
    }
    return Pair(w - w % 2, h - h % 2)
}

// Telefonun kodlayıcısı dəstəkləmədiyi ölçüdə xəta verir, ona görə ən yüksək dəstəklənən ölçünü tapırıq
fun pqEffectiveSide(info: VideoInfo): Int {
    val srcSide = minOf(info.width, info.height)
    if (VideoState.resHeight == 0 || srcSide <= 0) return srcSide
    val want = VideoState.resHeight
    val caps = pqVideoCaps(pqMime()) ?: return want
    val fps = pqOutFps(info)
    var s = want
    while (s >= 360) {
        val d = pqDimsForSide(info, s)
        try {
            if (caps.areSizeAndRateSupported(d.first, d.second, fps) ||
                caps.areSizeAndRateSupported(d.second, d.first, fps)
            ) return s
        } catch (e: Exception) {
            return want
        }
        s = when {
            s > 1440 -> 1440
            s > 1080 -> 1080
            s > 720 -> 720
            else -> s - 120
        }
    }
    return want
}

fun pqOutDims(info: VideoInfo): Pair<Int, Int> {
    if (info.height <= 0 || info.width <= 0) return Pair(info.width, info.height)
    if (VideoState.resHeight == 0) return Pair(info.width, info.height)
    return pqDimsForSide(info, pqEffectiveSide(info))
}

fun pqEffectiveHeight(info: VideoInfo): Int {
    return pqOutDims(info).second
}

fun pqOutFps(info: VideoInfo): Double {
    val src = if (info.fps > 0.0) info.fps else 30.0
    val want = VideoState.fps
    return if (want > 0 && want < src - 0.5) want.toDouble() else src
}

fun pqBitrate(info: VideoInfo): Int {
    val d = pqOutDims(info)
    val bpp = when (VideoState.quality) {
        0 -> 0.25
        1 -> 0.15
        else -> 0.08
    }
    val codecFactor = if (VideoState.h265) 1.0 else 1.4
    val bps = d.first.toDouble() * d.second * pqOutFps(info) * bpp * codecFactor
    var maxB = 80_000_000.0
    val caps = pqVideoCaps(pqMime())
    if (caps != null) {
        try {
            maxB = minOf(maxB, caps.bitrateRange.upper.toDouble())
        } catch (e: Exception) {
        }
    }
    return bps.coerceIn(500_000.0, maxOf(500_000.0, maxB)).toInt()
}

fun estimatedMb(videoBps: Int, seconds: Double): Double {
    return (videoBps + AUDIO_BPS).toDouble() * seconds / 8.0 / 1_000_000.0
}

fun pqColorEffects(): List<Effect> {
    val list = ArrayList<Effect>()
    if (VideoState.bright != 0f) list.add(Brightness(VideoState.bright))
    if (VideoState.contrast != 0f) list.add(Contrast(VideoState.contrast))
    if (VideoState.sat != 0f) {
        list.add(HslAdjustment.Builder().adjustSaturation(VideoState.sat).build())
    }
    if (VideoState.warmth != 0f) {
        list.add(
            RgbAdjustment.Builder()
                .setRedScale(1f + VideoState.warmth * 0.2f)
                .setBlueScale(1f - VideoState.warmth * 0.2f)
                .build()
        )
    }
    return list
}

// ---------------------------------------------------------------- export

// ---------------------------------------------------------------- offline assistant

fun pqAdvise(info: VideoInfo, platform: Int): String {
    val notes = ArrayList<String>()
    val src = if (info.fps > 0.0) info.fps else 30.0
    val srcSide = minOf(info.width, info.height)
    when (platform) {
        0, 1 -> {
            val name = if (platform == 0) "TikTok" else "Instagram"
            VideoState.resHeight = 1080
            VideoState.h265 = false
            VideoState.quality = 0
            VideoState.fps = if (src > 60.5) 60 else 0
            notes.add("$name üçün: 1080p, H.264, maksimum keyfiyyət. Platforma videonu yenidən sıxır, yüksək bitrate bu zərəri azaldır.")
            if (src > 60.5) notes.add("FPS 60-a endirildi, bundan yüksək FPS çox vaxt faydasız olur.")
        }
        2 -> {
            VideoState.resHeight = if (srcSide >= 1080) 1440 else 1080
            VideoState.h265 = true
            VideoState.quality = 1
            VideoState.fps = 0
            notes.add("YouTube üçün: ${VideoState.resHeight}p, H.265, balanslı keyfiyyət. Yüksək ölçüdə YouTube çox vaxt daha yaxşı sıxma verir.")
        }
        else -> {
            VideoState.resHeight = 0
            VideoState.h265 = true
            VideoState.fps = 0
            val mbps = info.bitrateBps / 1_000_000.0
            VideoState.quality = if (mbps > 20.0) 2 else 1
            notes.add("Yalnız kiçiltmək üçün: ölçü və FPS olduğu kimi qalır, H.265 ilə ${if (VideoState.quality == 2) "kiçik ölçü" else "balanslı"} keyfiyyət.")
        }
    }
    if (srcSide in 1..719 && platform != 3) {
        notes.add("Mənbə video aşağı ölçülüdür. Böyütmə yeni detal yaratmır, nəticə yumşaq görünə bilər.")
    }
    if (info.bitrateBps in 1..3_999_999L) {
        notes.add("Mənbənin bitrate-i aşağıdır (${"%.1f".format(info.bitrateBps / 1_000_000.0)} Mbps). Keyfiyyət artmayacaq, ölçü yalnız böyüyəcək.")
    }
    if (info.fps in 0.1..23.5) {
        notes.add("Mənbə FPS-i aşağıdır, FPS artırmaq bu proqramda yoxdur.")
    }
    notes.add("Ayarlar aşağıda avtomatik dəyişdi, istəsən əl ilə düzəlt.")
    return notes.joinToString("\n")
}

fun pollProgress(t: Transformer) {
    val holder = ProgressHolder()
    val r = object : Runnable {
        override fun run() {
            if (activeTransformer !== t || !VideoState.busy) return
            val state = t.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                VideoState.progress = holder.progress
            }
            VideoState.handler.postDelayed(this, 500)
        }
    }
    VideoState.handler.postDelayed(r, 500)
}

fun runExport(
    app: Context,
    builder: Transformer.Builder,
    edited: EditedMediaItem,
    outFile: File,
    label: String,
    prefix: String
) {
    VideoState.busy = true
    VideoState.progress = 0
    VideoState.currentOut = outFile
    VideoState.status = "$label: başladı..."

    val transformer = builder
        .addListener(object : Transformer.Listener {
            override fun onCompleted(
                composition: Composition,
                exportResult: ExportResult
            ) {
                VideoState.progress = 100
                VideoState.status = "Qalereyaya yazılır..."
                val fmt = formatName(exportResult.videoMimeType)
                Thread {
                    val ok = saveToGallery(app, outFile, prefix)
                    val bytes = outFile.length()
                    if (ok) outFile.delete()
                    VideoState.handler.post {
                        VideoState.busy = false
                        VideoState.currentOut = null
                        VideoState.status = if (ok) {
                            "Hazırdır! Format: $fmt, ölçü: ${bytes / 1_000_000} MB. " +
                                "Qalereyada Movies/PrimeQuality qovluğuna bax"
                        } else {
                            "Emal bitdi, amma qalereyaya yazmaq alınmadı"
                        }
                    }
                }.start()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                outFile.delete()
                VideoState.busy = false
                VideoState.currentOut = null
                VideoState.status = "Xəta: ${exportException.errorCodeName} - ${exportException.message}"
            }
        })
        .build()

    activeTransformer = transformer
    try {
        transformer.start(edited, outFile.absolutePath)
        pollProgress(transformer)
    } catch (e: Exception) {
        outFile.delete()
        VideoState.busy = false
        VideoState.currentOut = null
        VideoState.status = "Başlamaq alınmadı: ${e.message}"
    }
}

fun cleanOldOutputs(app: Context) {
    val files = app.cacheDir.listFiles() ?: return
    for (f in files) {
        if (f.name.startsWith("out_") && f.name.endsWith(".mp4")) {
            f.delete()
        }
    }
}

fun startVideoExport(context: Context, uri: Uri, info: VideoInfo) {
    val app = context.applicationContext
    cleanOldOutputs(app)
    val outFile = File(app.cacheDir, "out_${System.currentTimeMillis()}.mp4")
    val bitrate = pqBitrate(info)

    val effects = ArrayList<Effect>()
    val want = VideoState.fps
    if (want > 0 && (info.fps <= 0.0 || want < info.fps - 0.5)) {
        effects.add(FrameDropEffect.createDefaultFrameDropEffect(want.toFloat()))
    }
    effects.addAll(pqColorEffects())
    val eff = pqEffectiveHeight(info)
    if (eff > 0 && eff != info.height) {
        effects.add(Presentation.createForHeight(eff))
    }

    val encoderFactory = DefaultEncoderFactory.Builder(app)
        .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder()
                .setBitrate(bitrate)
                .build()
        )
        .build()
    val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(Effects(emptyList(), effects))
        .build()
    val mime = if (VideoState.h265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
    val builder = Transformer.Builder(app)
        .setVideoMimeType(mime)
        .setEncoderFactory(encoderFactory)

    runExport(app, builder, edited, outFile, "Emal", "prime")
}

fun cancelExport() {
    try {
        activeTransformer?.cancel()
    } catch (e: Exception) {
    }
    VideoState.currentOut?.delete()
    VideoState.currentOut = null
    VideoState.busy = false
    VideoState.status = "Ləğv edildi"
    activeTransformer = null
}

// ---------------------------------------------------------------- image

fun pqLoadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    return try {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxSide) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, o2)
        } ?: return null
        var rot = 0f
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val o = ExifInterface(s).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, 1
                    )
                    rot = when (o) {
                        6 -> 90f
                        3 -> 180f
                        8 -> 270f
                        else -> 0f
                    }
                }
            } catch (e: Exception) {
            }
        }
        if (rot != 0f) {
            val m = Matrix()
            m.postRotate(rot)
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } else {
            bmp
        }
    } catch (e: Exception) {
        null
    }
}

fun pqThumb(b: Bitmap): Bitmap {
    val m = maxOf(b.width, b.height)
    if (m <= 1024) return b
    val k = 1024f / m
    return Bitmap.createScaledBitmap(
        b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true
    )
}

fun pqChannel(c: Int, l: Int, r: Int, u: Int, d: Int, sh: Int, a: Float): Int {
    val v = ((c shr sh) and 255) * (1f + 4f * a) -
        a * (((l shr sh) and 255) + ((r shr sh) and 255) +
            ((u shr sh) and 255) + ((d shr sh) and 255))
    return v.toInt().coerceIn(0, 255) shl sh
}

fun pqSharpen(bmp: Bitmap, a: Float): Bitmap {
    if (a <= 0f) return bmp
    val w = bmp.width
    val h = bmp.height
    val src = IntArray(w * h)
    bmp.getPixels(src, 0, w, 0, 0, w, h)
    val dst = src.copyOf()
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val i = y * w + x
            val c = src[i]
            val l = src[i - 1]
            val r = src[i + 1]
            val u = src[i - w]
            val d = src[i + w]
            dst[i] = (c and 0xFF000000.toInt()) or
                pqChannel(c, l, r, u, d, 16, a) or
                pqChannel(c, l, r, u, d, 8, a) or
                pqChannel(c, l, r, u, d, 0, a)
        }
    }
    bmp.setPixels(dst, 0, w, 0, 0, w, h)
    return bmp
}

fun pqEnhanceBitmap(
    src: Bitmap, scale: Int, sharpen: Float,
    bright: Float, contrast: Float, sat: Float, warmth: Float
): Bitmap {
    var w = src.width * scale
    var h = src.height * scale
    val m = maxOf(w, h)
    if (m > 3840) {
        val k = 3840f / m
        w = (w * k).toInt()
        h = (h * k).toInt()
    }
    var cur = src
    while (cur.width < w) {
        val nw = minOf(cur.width * 2, w)
        val nh = minOf(cur.height * 2, h)
        cur = Bitmap.createScaledBitmap(cur, nw, nh, true)
    }
    if (cur.width != w) {
        cur = Bitmap.createScaledBitmap(cur, w, h, true)
    }

    val cm = ColorMatrix()
    cm.setSaturation(1f + sat / 100f)
    val c = 1f + contrast
    val t = 128f * (1f - c) + bright * 255f
    cm.postConcat(
        ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    val wm = ColorMatrix()
    wm.setScale(1f + warmth * 0.2f, 1f, 1f - warmth * 0.2f, 1f)
    cm.postConcat(wm)

    val out = Bitmap.createBitmap(cur.width, cur.height, Bitmap.Config.ARGB_8888)
    val paint = Paint()
    paint.isFilterBitmap = true
    paint.colorFilter = ColorMatrixColorFilter(cm)
    Canvas(out).drawBitmap(cur, 0f, 0f, paint)
    return pqSharpen(out, sharpen)
}

fun startImageEnhance(context: Context) {
    val src = ImgState.src ?: return
    if (ImgState.busy) return
    val app = context.applicationContext
    ImgState.busy = true
    ImgState.status = "Emal olunur..."
    val scale = ImgState.scale
    val sh = ImgState.sharpen
    val br = VideoState.bright
    val co = VideoState.contrast
    val sa = VideoState.sat
    val wa = VideoState.warmth
    Thread {
        try {
            val out = pqEnhanceBitmap(src, scale, sh, br, co, sa, wa)
            val ok = pqSaveBitmap(app, out)
            val msg = if (ok) {
                "Hazırdır! ${out.width} x ${out.height}. Qalereyada Pictures/PrimeQuality"
            } else {
                "Emal bitdi, amma qalereyaya yazmaq alınmadı"
            }
            val th = pqThumb(out)
            VideoState.handler.post {
                ImgState.preview = th
                ImgState.busy = false
                ImgState.status = msg
            }
        } catch (e: Throwable) {
            VideoState.handler.post {
                ImgState.busy = false
                ImgState.status = "Xəta: ${e.message}"
            }
        }
    }.start()
}

// ---------------------------------------------------------------- UI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HomeScreen()
            }
        }
    }
}

@Composable
fun ModeButton(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(enabled = enabled, onClick = onClick) { Text(text) }
    } else {
        OutlinedButton(enabled = enabled, onClick = onClick) { Text(text) }
    }
}

@Composable
fun OptionRow(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { idx, label ->
            ModeButton(label, idx == selected, enabled) { onSelect(idx) }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    Text("$label: ${"%.2f".format(value)}")
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        enabled = enabled
    )
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
fun VideoTab(context: Context, onPick: () -> Unit) {
    Button(enabled = !VideoState.busy, onClick = onPick) {
        Text("Video seç")
    }
    Spacer(Modifier.height(12.dp))

    val i = VideoState.info
    val u = VideoState.uri
    if (u == null) {
        Text("Video seçilməyib")
        return
    }
    if (i == null) return

    SectionTitle("Video haqqında")
    if (i.name.isNotEmpty()) Text("Ad: ${i.name}")
    if (i.folder.isNotEmpty()) Text("Qovluq: ${i.folder}")
    Text("Ölçü: ${i.width} x ${i.height}")
    Text("Müddət: ${"%.1f".format(i.seconds)} saniyə")
    if (i.fps > 0.0) Text("Kadr sürəti: ${"%.1f".format(i.fps)} FPS")
    else Text("Kadr sürəti: oxunmadı")
    if (i.codec.isNotEmpty()) Text("Format: ${formatName(i.codec)}")
    if (i.sizeBytes > 0L) Text("Fayl ölçüsü: ${i.sizeBytes / 1_000_000} MB")
    if (i.bitrateBps > 0L) {
        Text("Bitrate: ${"%.1f".format(i.bitrateBps / 1_000_000.0)} Mbps")
    }

    SectionTitle("Asistan")
    OptionRow(
        listOf("TikTok", "Instagram", "YouTube", "Yalnız kiçilt"),
        VideoState.platform,
        !VideoState.busy
    ) { VideoState.platform = it }
    Spacer(Modifier.height(8.dp))
    Button(
        enabled = !VideoState.busy,
        onClick = { VideoState.advice = pqAdvise(i, VideoState.platform) }
    ) {
        Text("Tövsiyə al")
    }
    if (VideoState.advice.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(VideoState.advice)
    }

    SectionTitle("FPS")
    OptionRow(
        FPS_OPTS.map { if (it == 0) "Orijinal" else "$it" },
        FPS_OPTS.indexOf(VideoState.fps),
        !VideoState.busy
    ) { VideoState.fps = FPS_OPTS[it] }
    val src = if (i.fps > 0.0) i.fps else 30.0
    if (VideoState.fps > 0 && VideoState.fps >= src - 0.5) {
        Spacer(Modifier.height(4.dp))
        Text("Diqqət: FPS artırmaq bu proqramda yoxdur, FPS orijinal qalacaq. Yalnız azaltmaq işləyir.")
    }

    SectionTitle("Ölçü (hündürlük)")
    OptionRow(
        listOf("Orijinal", "720p", "1080p", "2K", "4K"),
        RES_OPTS.indexOf(VideoState.resHeight),
        !VideoState.busy
    ) { VideoState.resHeight = RES_OPTS[it] }
    val effH = pqEffectiveSide(i)
    if (VideoState.resHeight > 0 && effH < VideoState.resHeight) {
        Spacer(Modifier.height(4.dp))
        Text("Telefonun kodlayıcısı ${VideoState.resHeight}p dəstəkləmir, nəticə ${effH}p olacaq. H.265 seçməyi yoxla.")
    }
    if (VideoState.resHeight > minOf(i.width, i.height) && i.height > 0) {
        Spacer(Modifier.height(4.dp))
        Text("Böyütmə yeni detal əlavə etmir, amma yükləmə platformaları daha yüksək ölçüdə daha az sıxa bilər.")
    }

    SectionTitle("Keyfiyyət və format")
    OptionRow(
        listOf("Maksimum", "Balanslı", "Kiçik ölçü"),
        VideoState.quality,
        !VideoState.busy
    ) { VideoState.quality = it }
    Spacer(Modifier.height(8.dp))
    OptionRow(
        listOf("H.265 (kiçik)", "H.264 (uyğun)"),
        if (VideoState.h265) 0 else 1,
        !VideoState.busy
    ) { VideoState.h265 = (it == 0) }

    SectionTitle("Nəticə")
    val d = pqOutDims(i)
    val ofps = pqOutFps(i)
    val vb = pqBitrate(i)
    val est = estimatedMb(vb, i.seconds)
    Text("Ölçü: ${d.first} x ${d.second}, ${"%.0f".format(ofps)} FPS")
    Text("Video bitrate: ${"%.1f".format(vb / 1_000_000.0)} Mbps")
    Text("Təxmini fayl ölçüsü: ${est.toInt()} MB")
    if (i.sizeBytes > 0L && est * 1_000_000.0 > i.sizeBytes) {
        Text("Diqqət: nəticə orijinaldan böyük ola bilər. Keyfiyyəti və ya ölçünü azalt.")
    }
    Spacer(Modifier.height(12.dp))
    Button(
        enabled = !VideoState.busy,
        onClick = { startVideoExport(context, u, i) }
    ) {
        Text("Başlat")
    }
}

@Composable
fun ColorTab() {
    val busy = VideoState.busy || ImgState.busy
    Text("Bu ayarlar həm videoya, həm şəkilə tətbiq olunur.")
    SectionTitle("Hazır rənglər")
    OptionRow(
        listOf("Normal", "İsti", "Soyuq", "Ağ-qara", "Canlı"),
        -1,
        !busy
    ) {
        when (it) {
            0 -> { VideoState.bright = 0f; VideoState.contrast = 0f; VideoState.sat = 0f; VideoState.warmth = 0f }
            1 -> { VideoState.bright = 0f; VideoState.contrast = 0.05f; VideoState.sat = 10f; VideoState.warmth = 0.6f }
            2 -> { VideoState.bright = 0f; VideoState.contrast = 0.05f; VideoState.sat = 0f; VideoState.warmth = -0.6f }
            3 -> { VideoState.bright = 0f; VideoState.contrast = 0.1f; VideoState.sat = -100f; VideoState.warmth = 0f }
            else -> { VideoState.bright = 0.02f; VideoState.contrast = 0.15f; VideoState.sat = 40f; VideoState.warmth = 0f }
        }
    }
    SectionTitle("Əl ilə")
    LabeledSlider("Parlaqlıq", VideoState.bright, -0.3f..0.3f, !busy) { VideoState.bright = it }
    LabeledSlider("Kontrast", VideoState.contrast, -0.5f..0.5f, !busy) { VideoState.contrast = it }
    LabeledSlider("Doyğunluq", VideoState.sat, -100f..100f, !busy) { VideoState.sat = it }
    LabeledSlider("İstilik (soyuq ↔ isti)", VideoState.warmth, -1f..1f, !busy) { VideoState.warmth = it }
}

@Composable
fun ImageTab(context: Context, onPick: () -> Unit) {
    Button(enabled = !ImgState.busy, onClick = onPick) {
        Text("Şəkil seç")
    }
    Spacer(Modifier.height(12.dp))
    val src = ImgState.src
    if (src == null) {
        Text("Şəkil seçilməyib")
        return
    }
    Text("Orijinal: ${src.width} x ${src.height}")
    val pv = ImgState.preview
    if (pv != null) {
        Spacer(Modifier.height(8.dp))
        Image(
            bitmap = pv.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            contentScale = ContentScale.Fit
        )
    }

    SectionTitle("Böyütmə")
    OptionRow(
        listOf("1x", "2x", "4x"),
        when (ImgState.scale) { 1 -> 0; 2 -> 1; else -> 2 },
        !ImgState.busy
    ) { ImgState.scale = when (it) { 0 -> 1; 1 -> 2; else -> 4 } }

    SectionTitle("Kəskinlik")
    LabeledSlider("Güc", ImgState.sharpen, 0f..1f, !ImgState.busy) { ImgState.sharpen = it }
    Text("Rəng ayarları üçün Rəng bölməsinə bax.")
    Text("Nəticə maksimum 3840 piksel ola bilər.")

    Spacer(Modifier.height(12.dp))
    Button(
        enabled = !ImgState.busy,
        onClick = { startImageEnhance(context) }
    ) {
        Text("Keyfiyyəti artır")
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !VideoState.busy) {
            VideoState.uri = uri
            VideoState.info = null
            VideoState.status = "Video oxunur..."
            VideoState.advice = ""
            val app = context.applicationContext
            Thread {
                val i = readInfo(app, uri)
                VideoState.handler.post {
                    VideoState.info = i
                    VideoState.status =
                        if (i == null) "Videonun məlumatı oxunmadı" else ""
                }
            }.start()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !ImgState.busy) {
            ImgState.status = "Şəkil oxunur..."
            val app = context.applicationContext
            Thread {
                val b = pqLoadBitmap(app, uri, 3840)
                VideoState.handler.post {
                    ImgState.src = b
                    ImgState.preview = if (b == null) null else pqThumb(b)
                    ImgState.status = if (b == null) "Şəkil oxunmadı" else ""
                }
            }.start()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Prime Quality", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Video") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Rəng") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Şəkil") })
        }
        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> VideoTab(context) { videoPicker.launch("video/*") }
            1 -> ColorTab()
            else -> ImageTab(context) { imagePicker.launch("image/*") }
        }

        Spacer(Modifier.height(16.dp))
        if (VideoState.busy) {
            Text("Emal: ${VideoState.progress}%")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { cancelExport() }) {
                Text("Ləğv et")
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(if (tab == 2) ImgState.status else VideoState.status)
    }
}
