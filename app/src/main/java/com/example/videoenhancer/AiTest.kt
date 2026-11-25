package com.example.videoenhancer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Upscaler(context: Context) {
    private val interpreter: Interpreter
    private val nchw: Boolean
    private val tile: Int
    private val outTile: Int
    private val scale: Int

    init {
        val bytes = context.assets.open("esrgan.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        val options = Interpreter.Options()
        options.setNumThreads(4)
        interpreter = Interpreter(model, options)
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        nchw = inShape[1] == 3
        tile = if (nchw) inShape[2] else inShape[1]
        outTile = if (nchw) outShape[2] else outShape[1]
        scale = outTile / tile
    }

    private fun to8(v: Float): Int {
        return (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }

    fun upscale(src: Bitmap, onProgress: (Int, Int) -> Unit): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val margin = 16
        val step = tile - 2 * margin
        val result = Bitmap.createBitmap(
            w * scale, h * scale, Bitmap.Config.ARGB_8888
        )

        val inBuf = ByteBuffer.allocateDirect(4 * 3 * tile * tile)
            .order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(4 * 3 * outTile * outTile)
            .order(ByteOrder.nativeOrder())
        val inF = inBuf.asFloatBuffer()

        val tilesX = (w + step - 1) / step
        val tilesY = (h + step - 1) / step
        val total = tilesX * tilesY
        var done = 0
        val plane = tile * tile
        val outPlane = outTile * outTile
        val off = margin * scale

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * step
                val y0 = ty * step

                for (y in 0 until tile) {
                    val sy = (y0 - margin + y).coerceIn(0, h - 1)
                    for (x in 0 until tile) {
                        val sx = (x0 - margin + x).coerceIn(0, w - 1)
                        val p = pixels[sy * w + sx]
                        val r = ((p shr 16) and 0xFF) / 255f
                        val g = ((p shr 8) and 0xFF) / 255f
                        val b = (p and 0xFF) / 255f
                        val pos = y * tile + x
                        if (nchw) {
                            inF.put(pos, r)
                            inF.put(plane + pos, g)
                            inF.put(2 * plane + pos, b)
                        } else {
                            inF.put(pos * 3, r)
                            inF.put(pos * 3 + 1, g)
                            inF.put(pos * 3 + 2, b)
                        }
                    }
                }

                inBuf.rewind()
                outBuf.rewind()
                interpreter.run(inBuf, outBuf)
                outBuf.rewind()
                val outF = outBuf.asFloatBuffer()

                val cw = minOf(step, w - x0)
                val ch = minOf(step, h - y0)
                val ow = cw * scale
                val oh = ch * scale
                val tilePixels = IntArray(ow * oh)

                for (ly in 0 until oh) {
                    for (lx in 0 until ow) {
                        val pos = (off + ly) * outTile + (off + lx)
                        val r: Float
                        val g: Float
                        val b: Float
                        if (nchw) {
                            r = outF.get(pos)
                            g = outF.get(outPlane + pos)
                            b = outF.get(2 * outPlane + pos)
                        } else {
                            r = outF.get(pos * 3)
                            g = outF.get(pos * 3 + 1)
                            b = outF.get(pos * 3 + 2)
                        }
                        tilePixels[ly * ow + lx] =
                            (0xFF shl 24) or (to8(r) shl 16) or
                                (to8(g) shl 8) or to8(b)
                    }
                }
                result.setPixels(
                    tilePixels, 0, ow, x0 * scale, y0 * scale, ow, oh
                )
                done++
                onProgress(done, total)
            }
        }
        return result
    }
}

fun loadScaledBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        if (bmp == null) {
            null
        } else {
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > maxSide) {
                val f = maxSide.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * f).toInt().coerceAtLeast(1),
                    (bmp.height * f).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
        }
    } catch (e: Exception) {
        null
    }
}

fun saveImageToGallery(context: Context, bmp: Bitmap): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val values = ContentValues().apply {
        put(
            MediaStore.Images.Media.DISPLAY_NAME,
            "ai_${System.currentTimeMillis()}.png"
        )
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/VideoEnhancer"
        )
    }
    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
    ) ?: return false
    context.contentResolver.openOutputStream(uri)?.use {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    return true
}

@Composable
fun AiTestSection() {
    val context = LocalContext.current
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("Şəkil seç") }
    var busy by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val bmp = loadScaledBitmap(context, uri, 512)
            original = bmp
            result = null
            status = if (bmp == null) {
                "Şəkil oxunmadı"
            } else {
                "Şəkil hazırdır: ${bmp.width} x ${bmp.height}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AI test (4x)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { picker.launch("image/*") }) {
            Text("Şəkil seç")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy && original != null,
            onClick = {
                val src = original
                if (src != null) {
                    busy = true
                    status = "Model yüklənir..."
                    Thread {
                        try {
                            val start = System.currentTimeMillis()
                            val up = Upscaler(context)
                            val out = up.upscale(src) { d, t ->
                                handler.post {
                                    status = "Emal olunur: ${d * 100 / t}% ($d/$t)"
                                }
                            }
                            val sec = (System.currentTimeMillis() - start) / 1000.0
                            val saved = saveImageToGallery(context, out)
                            val tail = if (saved) {
                                " Pictures/VideoEnhancer qovluğunda saxlanıldı"
                            } else {
                                ""
                            }
                            handler.post {
                                result = out
                                busy = false
                                status = "Hazırdır: ${out.width} x ${out.height}, " +
                                    "${"%.1f".format(sec)} san." + tail
                            }
                        } catch (e: Throwable) {
                            handler.post {
                                busy = false
                                status = "Xəta: ${e.message}"
                            }
                        }
                    }.start()
                }
            }
        ) {
            Text("AI ilə böyüt")
        }
        Spacer(Modifier.height(12.dp))
        Text(status)
        Spacer(Modifier.height(12.dp))

        val o = original
        if (o != null) {
            Text("Əvvəl:")
            Image(
                bitmap = o.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
        val r = result
        if (r != null) {
            Text("AI ilə sonra:")
            Image(
                bitmap = r.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

class RootActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RootScreen()
            }
        }
    }
}

@Composable
fun RootScreen() {
    var tab by remember { mutableStateOf(1) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { tab = 0 }) { Text("Video (2x)") }
            Button(onClick = { tab = 1 }) { Text("AI test") }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (tab == 0) HomeScreen() else AiTestSection()
        }
    }
}
