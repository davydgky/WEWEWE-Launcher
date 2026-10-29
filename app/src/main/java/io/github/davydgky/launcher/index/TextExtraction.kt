package io.github.davydgky.launcher.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Plain text out of documents: text files directly, PDFs with the platform PDF renderer. */
object TextExtraction {

    private const val TAG = "TextExtraction"
    private const val MAX_TEXT_BYTES = 256 * 1024L
    private const val MAX_PDF_PAGES = 40
    const val MAX_CHARS = 20_000

    fun document(context: Context, doc: DocumentEntry): String = runCatching {
        when {
            doc.extension in DocumentEntry.TEXT_EXTENSIONS -> if (doc.size > MAX_TEXT_BYTES) "" else
                context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().decodeToString() }.orEmpty()
            doc.extension == "pdf" -> pdf(context, doc.uri)
            else -> ""
        }
    }.onFailure { Log.w(TAG, "Cannot read ${doc.uri}", it) }.getOrDefault("").let(::clean)

    private fun pdf(context: Context, uri: Uri): String {
        // Text extraction came with Android 15's PDF module (SDK extension 13).
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) < 13) return ""
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                buildString {
                    for (index in 0 until minOf(renderer.pageCount, MAX_PDF_PAGES)) {
                        renderer.openPage(index).use { page ->
                            page.textContents.forEach { append(it.text).append('\n') }
                        }
                        if (length >= MAX_CHARS) break
                    }
                }
            }
        }
    }

    /** Collapses runs of spaces and blank lines; keeps line structure for the result preview. */
    fun clean(text: String): String = text.lineSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .take(MAX_CHARS)
}

/** On-device OCR with ML Kit's bundled Latin model. */
class TextRecognizer : AutoCloseable {

    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): String {
        val text = suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return TextExtraction.clean(text)
    }

    override fun close() = client.close()
}

/**
 * What a picture shows, with ML Kit's bundled on-device model: labels such as "Dog", "Beach", "Cake"
 * (in English, as the model names them; [ImageLabelNames] has them in German).
 */
class PictureLabeler : AutoCloseable {

    private val client = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build())

    suspend fun label(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels -> cont.resume(labels.sortedByDescending { it.confidence }.map { it.text }.take(MAX_LABELS)) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    override fun close() = client.close()

    private companion object {
        /** Below this the labels are guesses that would make searches noisy. */
        const val MIN_CONFIDENCE = 0.72f
        const val MAX_LABELS = 6
    }
}

/** A picture for recognition: large photos are scaled down, text stays readable and memory stays low. */
fun decodeForRecognition(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val longSide = maxOf(info.size.width, info.size.height)
        if (longSide > MAX_DECODE_SIDE) {
            val scale = MAX_DECODE_SIDE.toFloat() / longSide
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

private const val MAX_DECODE_SIDE = 2048
