package fr.hybridcoach.export

import android.content.*
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import fr.hybridcoach.domain.newId
import java.io.File

object AndroidExporter {
    fun write(context: Context, document: PreparationDocument, pdf: Boolean): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        directory
            .listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86400000L }
            ?.forEach { it.delete() }
        val safeName =
            document.preparation.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(60).ifBlank {
                "preparation"
            }
        val file = File(directory, "${safeName}_${newId()}.${if(pdf) "pdf" else "json"}")
        if (pdf) writePdf(context, file, PreparationExport.lines(document))
        else file.writeText(PreparationExport.json(document), Charsets.UTF_8)
        return file
    }

    private fun writePdf(context: Context, file: File, lines: List<String>) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument().use { document ->
            val regular =
                context.assets.open("fonts/LiberationSans-Regular.ttf").use {
                    PDType0Font.load(document, it)
                }
            val bold =
                context.assets.open("fonts/LiberationSans-Bold.ttf").use {
                    PDType0Font.load(document, it)
                }
            val support = mutableMapOf<Int, Boolean>()
            fun safeText(raw: String): String = buildString {
                raw.codePoints().forEach { cp ->
                    if (cp == 9) append("    ")
                    else if (cp >= 32) {
                        val glyph = String(Character.toChars(cp))
                        val supported =
                            support.getOrPut(cp) {
                                try {
                                    regular.getStringWidth(glyph)
                                    bold.getStringWidth(glyph)
                                    true
                                } catch (_: IllegalArgumentException) {
                                    false
                                }
                            }
                        append(if (supported) glyph else "?")
                    }
                }
            }
            var stream: PDPageContentStream? = null
            var y = 0f
            fun text(value: String, x: Float, baseline: Float, font: PDType0Font, size: Float) {
                stream!!.beginText()
                stream!!.setFont(font, size)
                stream!!.newLineAtOffset(x, baseline)
                stream!!.showText(value)
                stream!!.endText()
            }
            fun newPage() {
                stream?.close()
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                stream = PDPageContentStream(document, page)
                stream!!.setNonStrokingColor(66 / 255f, 104 / 255f, 48 / 255f)
                text("HYBRID COACH", 38f, 814f, bold, 9f)
                text("${document.numberOfPages}", 535f, 23f, regular, 9f)
                stream!!.setNonStrokingColor(28 / 255f, 42 / 255f, 34 / 255f)
                y = 784f
            }
            try {
                newPage()
                lines.drop(1).forEachIndexed { index, raw ->
                    val heading =
                        raw.startsWith("SEMAINE ") ||
                            raw in listOf("VUE GLOBALE", "SYNTHÈSE") ||
                            raw.startsWith("OBJECTIF ·")
                    val font = if (heading || index == 0) bold else regular
                    val size = if (index == 0) 20f else if (heading) 12f else 11f
                    if (heading && y < 100f) newPage()
                    raw.split('\n').forEach { paragraph ->
                        if (paragraph.isEmpty()) y -= 10f
                        var remaining = safeText(paragraph)
                        while (remaining.isNotEmpty()) {
                            if (y < 52f) newPage()
                            var width = 0f
                            var end = 0
                            var lastSpace = -1
                            while (end < remaining.length) {
                                val cp = remaining.codePointAt(end)
                                val next = end + Character.charCount(cp)
                                val glyph = remaining.substring(end, next)
                                val nextWidth = width + font.getStringWidth(glyph) * size / 1000
                                if (nextWidth > 519f && end > 0) break
                                if (cp == 32) lastSpace = end
                                width = nextWidth
                                end = next
                            }
                            val split =
                                if (end < remaining.length && lastSpace > 0) lastSpace else end
                            text(remaining.take(split), 38f, y, font, size)
                            remaining = remaining.drop(split).trimStart()
                            y -= if (index == 0) 28f else if (heading) 21f else 16f
                        }
                    }
                }
                stream?.close()
                stream = null
                document.save(file)
            } finally {
                stream?.close()
            }
        }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType(if (file.extension == "pdf") "application/pdf" else "application/json")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(context.contentResolver, "Préparation Hybrid Coach", uri)
        context.startActivity(Intent.createChooser(intent, "Partager la préparation"))
    }
}
