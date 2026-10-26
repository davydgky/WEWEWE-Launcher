package io.github.davydgky.launcher.index

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Environment
import android.util.Log
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.sources.DocumentsSource
import io.github.davydgky.launcher.sources.ImagesSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reads text out of pictures (on-device OCR) and documents. Runs only while the device is charging:
 * when it is unplugged, Android stops the job, and the work done so far is kept per file.
 */
class ContentIndexJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val finished = runCatching { ContentIndexer(applicationContext).run() }
                .onFailure { Log.w(TAG, "Content indexing failed", it) }
                .isSuccess
            jobFinished(params, !finished)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        // Run again the next time the device charges.
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ContentIndexJob"
        private const val JOB_ID = 1

        /** Asks for a run the next time the device charges; does nothing if one is already planned. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ContentIndexJob::class.java))
                .setRequiresCharging(true)
                .build()
            scheduler.schedule(job)
        }
    }
}

/** The work of [ContentIndexJob]: documents first (fast), then pictures, newest first. */
class ContentIndexer(private val context: Context) {

    private val app = context.launcherApp
    private val store = app.indexStore

    suspend fun run() {
        val disabled = app.settings.prefs.first().disabledSources
        if (DocumentsSource.ID !in disabled && Environment.isExternalStorageManager()) indexDocuments()
        if (ImagesSource.ID !in disabled && ImagesSource.hasAccess(context)) recogniseImages()
    }

    private suspend fun indexDocuments() {
        val known = store.docTexts()
        val pending = MediaCatalog.documents(context).filter { doc ->
            doc.hasReadableText && known[doc.id]?.version != doc.version
        }
        if (pending.isEmpty()) return
        var sinceRefresh = 0
        for (doc in pending) {
            currentCoroutineContext().ensureActive()
            store.putDocText(doc.id, doc.version, TextExtraction.document(context, doc))
            if (++sinceRefresh == REFRESH_EVERY) {
                sinceRefresh = 0
                app.search.reload(DocumentsSource.ID)
            }
        }
        app.search.reload(DocumentsSource.ID)
    }

    private suspend fun recogniseImages() {
        val known = store.imageTexts()
        val pending = MediaCatalog.images(context).filter { image ->
            image.worthRecognising && known[image.id]?.version != image.version
        }
        if (pending.isEmpty()) return
        Log.i(TAG, "Recognising text in ${pending.size} pictures")
        TextRecognizer().use { recognizer ->
            var sinceRefresh = 0
            for (image in pending) {
                currentCoroutineContext().ensureActive()
                val text = runCatching { recognizer.recognize(context, image.uri) }
                    .onFailure { Log.w(TAG, "OCR failed for ${image.uri}", it) }
                    .getOrDefault("")
                store.putImageText(image.id, image.version, text)
                if (++sinceRefresh == REFRESH_EVERY) {
                    sinceRefresh = 0
                    app.search.reload(ImagesSource.ID)
                }
            }
        }
        Log.i(TAG, "Text recognition finished")
        app.search.reload(ImagesSource.ID)
    }

    private companion object {
        const val TAG = "ContentIndexer"

        /** New text becomes searchable in batches, so a long run shows progress. */
        const val REFRESH_EVERY = 25
    }
}
