package com.example.videoenhancer

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

const val MAX_SECONDS = 120

data class VideoInfo(val width: Int, val height: Int, val seconds: Double)

var activeTransformer: Transformer? = null

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
        VideoInfo(w, h, ms / 1000.0)
    } catch (e: Exception) {
        null
    } finally {
        r.release()
    }
}

fun saveToGallery(context: Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val values = ContentValues().apply {
        put(
            MediaStore.Video.Media.DISPLAY_NAME,
            "enhanced_${System.currentTimeMillis()}.mp4"
        )
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(
            MediaStore.Video.Media.RELATIVE_PATH,
            Environment.DIRECTORY_MOVIES + "/VideoEnhancer"
        )
    }
    val resolver = context.contentResolver
    val target = resolver.insert(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
    ) ?: return false
    resolver.openOutputStream(target)?.use { out ->
        file.inputStream().use { it.copyTo(out) }
    }
    return true
}

fun startEnhance(
    context: Context,
    uri: Uri,
    info: VideoInfo,
    onStatus: (String) -> Unit
) {
    val outFile = File(
        context.cacheDir, "out_${System.currentTimeMillis()}.mp4"
    )
    val targetHeight = minOf(info.height * 2, 2160)

    val effects = Effects(
        emptyList(),
        listOf(Presentation.createForHeight(targetHeight))
    )
    val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(effects)
        .build()

    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(
                composition: Composition,
                exportResult: ExportResult
            ) {
                val ok = saveToGallery(context, outFile)
                outFile.delete()
                onStatus(
                    if (ok) "Hazırdır! Qalereyada Movies/VideoEnhancer qovluğuna bax"
                    else "Emal bitdi, amma qalereyaya yazmaq alınmadı"
                )
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                onStatus("Xəta: ${exportException.message}")
            }
        })
        .build()

    activeTransformer = transformer
    onStatus("Emal olunur, gözləyin...")
    transformer.start(edited, outFile.absolutePath)
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
fun HomeScreen() {
    val context = LocalContext.current
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var status by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        videoUri = uri
        info = if (uri != null) readInfo(context, uri) else null
        status = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Video Enhancer", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { picker.launch("video/*") }) {
            Text("Video seç")
        }
        Spacer(Modifier.height(16.dp))

        val i = info
        val u = videoUri
        if (u == null) {
            Text("Video seçilməyib")
        } else if (i == null) {
            Text("Videonun məlumatı oxunmadı")
        } else {
            val sec = "%.1f".format(i.seconds)
            Text("Ölçü: ${i.width} x ${i.height}")
            Text("Müddət: $sec saniyə")
            Spacer(Modifier.height(8.dp))
            if (i.seconds > MAX_SECONDS) {
                Text("Video 2 dəqiqədən uzundur, qısa video seç")
            } else {
                Button(onClick = {
                    startEnhance(context, u, i) { status = it }
                }) {
                    Text("Keyfiyyəti artır (2x)")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(status)
    }
}
