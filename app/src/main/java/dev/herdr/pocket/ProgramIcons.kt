package dev.herdr.pocket

import android.content.Context
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Herdr desktop's program icons, read from the person's own desktop (its icons folder) and kept on the phone. */
object ProgramIcons {
    private val NAME = Regex("^icons/[a-z0-9._-]{1,64}\\.svg$")
    private val _svgs = MutableStateFlow<Map<String, String>>(emptyMap())
    /** SVG text by the program table's icon path ("icons/claude.svg"). */
    val svgs = _svgs.asStateFlow()
    private val fetched: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val bitmaps = ConcurrentHashMap<String, ImageBitmap>()
    @Volatile private var dir: File? = null

    fun valid(icon: String) = NAME.matches(icon)
    /** Read from a desktop since this launch. */
    fun fresh(icon: String) = icon in fetched

    fun load(context: Context) {
        val folder = File(context.filesDir, "icons").apply { mkdirs() }
        dir = folder
        val cached = folder.listFiles { f -> f.name.endsWith(".svg") }.orEmpty()
            .associate { "icons/${it.name}" to it.readText() }.filterKeys(::valid)
        _svgs.update { cached + it }
    }

    fun save(icon: String, svg: String) {
        if (!valid(icon)) return
        fetched += icon
        if (_svgs.value[icon] == svg) return
        runCatching { dir?.let { File(it, icon.substringAfter('/')).writeText(svg) } }
        bitmaps.keys.removeIf { it.startsWith("$icon|") }
        _svgs.update { it + (icon to svg) }
    }

    /** [svg] drawn at [px] square; `currentColor` becomes [color] (white for icons the screen tints). */
    fun render(icon: String, svg: String, px: Int, color: String): ImageBitmap? = bitmaps["$icon|$px|$color"] ?: runCatching {
        val doc = SVG.getFromString(svg.replace("currentColor", color))
        doc.setDocumentWidth("100%"); doc.setDocumentHeight("100%")
        val bitmap = createBitmap(px, px)
        doc.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, px.toFloat(), px.toFloat()))
        bitmap.asImageBitmap()
    }.getOrNull()?.also { bitmaps["$icon|$px|$color"] = it }
}
