package com.advancedvideoplayer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.advancedvideoplayer.data.SpecialFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Finds and decodes the logo for a [SpecialFormat] from assets/logos/.
 *
 * The old code built the asset path from a hard-coded file name ("logos/dolby_atmos.png",
 * "logos/dts_hd.png" …) while the shipped files are "Dolby-Atmos-Logo.png", "DTS_HD.png" – on
 * Android's case-sensitive asset system that is simply a miss, so the logo "failed
 * intermittently" (depending on whether a text fallback happened to be drawn). Files are now
 * looked up by *normalised* name (see [SpecialFormat.logoKeys]) so any reasonable file name
 * works, and the lookup is done once.
 */
object LogoRepository {

    private const val DIR = "logos"

    @Volatile private var index: Map<String, String>? = null
    private val bitmaps = LruCache<String, ImageBitmap>(24)
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** "Dolby-Atmos-Logo.png" → "dolbyatmos" */
    internal fun normalize(fileName: String): String {
        val base = fileName.substringBeforeLast('.').lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return if (base.length > 4 && base.endsWith("logo")) base.removeSuffix("logo") else base
    }

    private fun index(context: Context): Map<String, String> {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val built = try {
                context.applicationContext.assets.list(DIR).orEmpty()
                    .filter { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS }
                    .associateBy({ normalize(it) }, { "$DIR/$it" })
            } catch (_: Throwable) { emptyMap() }
            index = built
            return built
        }
    }

    /** Asset path of the logo file for [format], or null when the project ships none. */
    fun resolve(context: Context, format: SpecialFormat): String? {
        val idx = index(context)
        return format.logoKeys.firstNotNullOfOrNull { idx[it] }
    }

    /** Decodes the logo (cached). Null if there is none or it cannot be decoded – never throws. */
    suspend fun load(context: Context, format: SpecialFormat): ImageBitmap? {
        val path = resolve(context, format) ?: return null
        bitmaps.get(path)?.let { return it }
        if (path in failed) return null
        return withContext(Dispatchers.IO) {
            try {
                context.applicationContext.assets.open(path).use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }?.also { bitmaps.put(path, it) } ?: run { failed += path; null }
            } catch (t: Throwable) {
                failed += path
                null
            }
        }
    }

    private val EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")
}

private sealed interface LogoState {
    data object Loading : LogoState
    data class Ready(val bitmap: ImageBitmap) : LogoState
    data object Missing : LogoState
}

/** A logo image when the asset exists, otherwise a coloured text badge. Never empty, never crashes. */
@Composable
fun FormatBadge(format: SpecialFormat) {
    val context = LocalContext.current
    val hasAsset = remember(format) { LogoRepository.resolve(context, format) != null }

    if (!hasAsset) {
        TextBadge(format)
        return
    }
    val state by produceState<LogoState>(LogoState.Loading, format) {
        val bmp = LogoRepository.load(context, format)
        value = if (bmp != null) LogoState.Ready(bmp) else LogoState.Missing
    }
    when (val s = state) {
        is LogoState.Ready -> Image(
            bitmap = s.bitmap,
            contentDescription = format.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(30.dp).padding(vertical = 2.dp)
        )
        LogoState.Loading -> Spacer(Modifier.height(30.dp).width(56.dp)) // reserve space, no flicker
        LogoState.Missing -> TextBadge(format)
    }
}

@Composable
private fun TextBadge(format: SpecialFormat) {
    val bg = when (format.family) {
        SpecialFormat.Family.DOLBY -> Color(0xFF00B4E4)
        SpecialFormat.Family.DTS -> Color(0xFFE31C25)
        SpecialFormat.Family.HDR -> Color(0xFFB8860B)
        SpecialFormat.Family.CODEC -> Color(0xFF7B61FF)
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
        Text(
            text = format.displayName,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
