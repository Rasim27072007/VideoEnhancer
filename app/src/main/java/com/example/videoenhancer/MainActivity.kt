package com.example.videoenhancer

import android.content.ContentValues
import android.content.Context
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
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

data class VideoInfo(
    val width: Int,
    val height: Int,
    val seconds: Double,
    val sizeBytes: Long
)

var activeTransformer: Transformer? = null

object VideoState {
    var uri by mutableStateOf<Uri?>(null)
    var info by mutableStateOf<VideoInfo?>(null)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var mode by mutableStateOf(0)
    var targetMb by mutableStateOf(550f)
    var currentOut: File? = null
    val handler = Handler(Looper.getMainLooper())
}

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
        if (rot == 90 || rot == 270) {
            val t = w
            w = h
            h = t
        }
        VideoInfo(w, h, ms / 1000.0, readSize(context, uri))
    } catch (e: Exception) {
        null
    } finally {
        r.release()
    }
}

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
            Environment.DIRECTORY_MOVIES + "/VideoEnhancer"
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

fun formatName(mime: String?): String {
    if (mime == null) return "naməlum"
    if (mime == "video/hevc") return "H.265 (HEVC)"
    if (mime == "video/avc") return "H.264 (AVC)"
    return mime
}

fun videoBitrateFor(targetMb: Float, seconds: Double): Int {
    if (seconds <= 0.0) return 0
    val totalBps = targetMb * 1_000_000.0 * 8.0 / seconds
    val v = totalBps - AUDIO_BPS
    return v.coerceIn(300_000.0, 100_000_000.0).toInt()
}

fun estimatedMb(videoBps: Int, seconds: Double): Double {
    return (videoBps + AUDIO_BPS).toDouble() * seconds / 8.0 / 1_000_000.0
}

fun defaultTargetMb(info: VideoInfo): Float {
    val sizeMb = info.sizeBytes / 1_000_000f
    if (sizeMb <= 0f) return 550f
    return if (sizeMb * 0.8f >= 550f) 550f else maxOf(50f, sizeMb * 0.5f)
}

fun compressWarning(info: VideoInfo, targetMb: Float, videoBps: Int): String? {
    if (info.seconds <= 0.0) return "Videonun müddəti oxunmadı"
    if (info.sizeBytes <= 0L) return null
    val sizeMb = info.sizeBytes / 1_000_000f
    if (targetMb >= sizeMb * 0.95f) {
        return "Hədəf ölçü orijinala yaxındır, sıxmanın faydası olmayacaq"
    }
    val origBps = info.sizeBytes * 8.0 / info.seconds
    if (videoBps <= 300_000) {
        return "Hədəf çox kiçikdir, keyfiyyət çox pis ola bilər"
    }
    if (videoBps < origBps * 0.15) {
        return "Güclü sıxma: keyfiyyət nəzərəçarpacaq düşə bilər"
    }
    return null
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
                                "Qalereyada Movies/VideoEnhancer qovluğuna bax"
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
                VideoState.status = "Xəta: ${exportException.message}"
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

fun startCompress(context: Context, uri: Uri, info: VideoInfo, targetMb: Float) {
    val app = context.applicationContext
    cleanOldOutputs(app)
    val outFile = File(app.cacheDir, "out_${System.currentTimeMillis()}.mp4")
    val bitrate = videoBitrateFor(targetMb, info.seconds)

    val encoderFactory = DefaultEncoderFactory.Builder(app)
        .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder().setBitrate(bitrate).build()
        )
        .build()
    val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).build()
    val builder = Transformer.Builder(app)
        .setVideoMimeType(MimeTypes.VIDEO_H265)
        .setEncoderFactory(encoderFactory)

    runExport(app, builder, edited, outFile, "Sıxma", "compressed")
}

fun startEnhance(context: Context, uri: Uri, info: VideoInfo) {
    val app = context.applicationContext
    cleanOldOutputs(app)
    val outFile = File(app.cacheDir, "out_${System.currentTimeMillis()}.mp4")
    val targetHeight = minOf(info.height * 2, 2160)

    val effects = Effects(
        emptyList(),
        listOf(Presentation.createForHeight(targetHeight))
    )
    val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(effects)
        .build()
    val builder = Transformer.Builder(app)

    runExport(app, builder, edited, outFile, "Emal", "enhanced")
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
fun HomeScreen() {
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !VideoState.busy) {
            VideoState.uri = uri
            VideoState.info = null
            VideoState.status = "Video oxunur..."
            val app = context.applicationContext
            Thread {
                val i = readInfo(app, uri)
                VideoState.handler.post {
                    VideoState.info = i
                    VideoState.status =
                        if (i == null) "Videonun məlumatı oxunmadı" else ""
                    if (i != null) {
                        VideoState.targetMb = defaultTargetMb(i)
                    }
                }
            }.start()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Video Enhancer", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !VideoState.busy,
            onClick = { picker.launch("video/*") }
        ) {
            Text("Video seç")
        }
        Spacer(Modifier.height(12.dp))

        val i = VideoState.info
        val u = VideoState.uri
        if (u == null) {
            Text("Video seçilməyib")
        } else if (i != null) {
            val sec = "%.1f".format(i.seconds)
            val sizeMb = i.sizeBytes / 1_000_000f
            Text("Ölçü: ${i.width} x ${i.height}")
            Text("Müddət: $sec saniyə")
            if (i.sizeBytes > 0L && i.seconds > 0.0) {
                val mbps = "%.1f".format(i.sizeBytes * 8.0 / i.seconds / 1_000_000.0)
                Text("Fayl ölçüsü: ${sizeMb.toInt()} MB (orta $mbps Mbps)")
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton(
                    "Sıxma (H.265)", VideoState.mode == 0, !VideoState.busy
                ) { VideoState.mode = 0 }
                ModeButton(
                    "2x böyüt", VideoState.mode == 1, !VideoState.busy
                ) { VideoState.mode = 1 }
            }
            Spacer(Modifier.height(12.dp))

            if (VideoState.mode == 0) {
                val maxMb = maxOf(sizeMb, 100f)
                val target = VideoState.targetMb.coerceIn(50f, maxMb)
                val vBps = videoBitrateFor(target, i.seconds)
                val est = estimatedMb(vBps, i.seconds)
                Text("Hədəf ölçü: ${target.toInt()} MB")
                Slider(
                    value = target,
                    onValueChange = { VideoState.targetMb = it },
                    valueRange = 50f..maxMb,
                    enabled = !VideoState.busy
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !VideoState.busy,
                        onClick = { VideoState.targetMb = 300f }
                    ) { Text("300") }
                    OutlinedButton(
                        enabled = !VideoState.busy,
                        onClick = { VideoState.targetMb = 500f }
                    ) { Text("500") }
                    OutlinedButton(
                        enabled = !VideoState.busy,
                        onClick = { VideoState.targetMb = 600f }
                    ) { Text("600") }
                }
                Spacer(Modifier.height(8.dp))
                val mbpsTxt = "%.1f".format(vBps / 1_000_000.0)
                Text("Təxmini video bitrate: $mbpsTxt Mbps")
                Text("Təxmini ölçü: ${est.toInt()} MB")
                val warn = compressWarning(i, target, vBps)
                if (warn != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(warn)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !VideoState.busy,
                    onClick = { startCompress(context, u, i, target) }
                ) {
                    Text("Sıx (H.265)")
                }
            } else {
                Text("Nəticə hündürlüyü: ${minOf(i.height * 2, 2160)} px")
                Spacer(Modifier.height(8.dp))
                if (i.seconds > MAX_SECONDS) {
                    Text("Video 2 dəqiqədən uzundur, qısa video seç")
                } else {
                    Button(
                        enabled = !VideoState.busy,
                        onClick = { startEnhance(context, u, i) }
                    ) {
                        Text("Keyfiyyəti artır (2x)")
                    }
                }
            }
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
        Text(VideoState.status)
    }
}
