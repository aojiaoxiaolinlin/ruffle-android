package rs.ruffle

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crash capture and user-initiated diagnostics sharing.
 *
 * Nothing is uploaded automatically: the player builds a zip with the current
 * diagnostics and hands it to the system share sheet so the user can review
 * and choose where it goes.
 */
internal object Diagnostics {
    private const val PERF_LOG = "perf.log"
    private const val PERF_PREVIOUS = "perf-previous.log"
    private const val CRASH_RUST = "crash-rust.log"
    private const val CRASH_JAVA = "crash-java.log"
    private const val CRASH_NATIVE = "native-crash.log"
    private const val CRASH_NATIVE_TRACE = "native-crash.trace.pb"
    private const val CRASH_ACK = "crash-ack"
    private const val MAX_LOG_BYTES = 2 * 1024 * 1024

    @Volatile
    private var handlerInstalled = false

    data class PendingCrash(val source: String, val summary: String, val timestamp: Long)

    private fun storageDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        if (external != null) {
            external.mkdirs()
            return external
        }
        return File(context.filesDir, "diagnostics").apply { mkdirs() }
    }

    fun installCrashHandler(context: Context) {
        if (handlerInstalled) return
        handlerInstalled = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeJavaCrash(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeJavaCrash(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter()
        error.printStackTrace(PrintWriter(stack))
        val text = buildString {
            appendLine("=== JAVA CRASH ===")
            appendLine("time: ${timestamp()}")
            appendLine("thread: ${thread.name}")
            appendLine(stack.toString())
            appendLine("--- device ---")
            append(deviceInfo(context))
            appendLine("--- perf.log tail ---")
            appendLine(tail(File(storageDir(context), PERF_LOG), 24_000))
        }
        runCatching { File(storageDir(context), CRASH_JAVA).writeText(text) }
    }

    fun deviceInfo(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("?")
        return buildString {
            appendLine("app: ${context.packageName} $version")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("render scale: ${PlayerSettings.renderScale(context)}")
            appendLine("quality: ${PlayerSettings.stageQuality(context)}")
            appendLine("frame rate: ${PlayerSettings.frameRate(context)}")
            appendLine("backend: ${PlayerSettings.graphicsBackend(context)}")
        }
    }

    /**
     * Native crashes (for example a SIGSEGV inside a GPU driver) bypass the
     * Rust panic hook. Android records them as an [ApplicationExitInfo]; copy
     * the newest one into a log so the next launch can offer it.
     */
    private fun captureExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        }.getOrNull() ?: return
        val exit = exits
            .filter {
                it.processName == context.packageName &&
                    (
                        it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                            it.reason == ApplicationExitInfo.REASON_CRASH ||
                            it.reason == ApplicationExitInfo.REASON_ANR
                        )
            }
            .maxByOrNull { it.timestamp } ?: return
        val dir = storageDir(context)
        val file = File(dir, CRASH_NATIVE)
        if (file.exists() && file.lastModified() >= exit.timestamp) return
        val trace = runCatching {
            exit.traceInputStream?.use { it.readBytes() }
        }.getOrNull()
        // Only native tombstones on Android 12+ are protobuf. Other exit
        // records can carry a text ANR trace, even when the final reason differs.
        val nativeTombstone = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        val text = buildString {
            appendLine("=== NATIVE/APP CRASH ===")
            appendLine("time: ${formatTime(exit.timestamp)}")
            appendLine("reason: ${reasonName(exit.reason)}")
            appendLine("status: ${exit.status}")
            appendLine("process: ${exit.processName}")
            appendLine("description: ${exit.description ?: "-"}")
            appendLine("rss: ${exit.rss} KB, pss: ${exit.pss} KB")
            if (nativeTombstone) {
                appendLine(
                    "trace: " + if (trace == null) {
                        "-"
                    } else {
                        "$CRASH_NATIVE_TRACE (${trace.size} bytes, raw protobuf)"
                    }
                )
            } else {
                appendLine("--- trace ---")
                append(trace?.toString(Charsets.UTF_8).orEmpty().take(64_000))
            }
        }
        runCatching {
            if (nativeTombstone && trace != null) {
                val traceFile = File(dir, CRASH_NATIVE_TRACE)
                val traceTmp = File(dir, "$CRASH_NATIVE_TRACE.tmp")
                traceTmp.writeBytes(trace)
                if (!traceTmp.renameTo(traceFile)) {
                    traceFile.writeBytes(trace)
                    traceTmp.delete()
                }
                traceFile.setLastModified(exit.timestamp)
            } else {
                File(dir, CRASH_NATIVE_TRACE).delete()
            }

            val header = File(dir, "$CRASH_NATIVE.tmp")
            header.writeText(text)
            if (!header.renameTo(file)) {
                file.writeText(text)
                header.delete()
            }
            file.setLastModified(exit.timestamp)
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        else -> reason.toString()
    }

    fun pendingCrash(context: Context): PendingCrash? {
        captureExitInfo(context)
        val dir = storageDir(context)
        val ackedAt = File(dir, CRASH_ACK).let { if (it.exists()) it.lastModified() else 0L }
        return listOf(CRASH_RUST, CRASH_JAVA, CRASH_NATIVE)
            .map { File(dir, it) }
            .filter { it.exists() && it.lastModified() > ackedAt }
            .maxByOrNull { it.lastModified() }
            ?.let { file ->
                PendingCrash(
                    source = file.name,
                    summary = runCatching { file.readText().take(8000) }
                        .getOrDefault(""),
                    timestamp = file.lastModified()
                )
            }
    }

    fun markCrashSeen(context: Context) {
        runCatching {
            File(storageDir(context), CRASH_ACK)
                .writeText(System.currentTimeMillis().toString())
        }
    }

    fun copyReport(context: Context, message: String?): Boolean {
        val report = buildReport(context, message)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("Ruffle diagnostics", report))
        Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        return true
    }

    fun shareReport(context: Context, message: String?): Boolean {
        val bundle = runCatching { createBundle(context, message) }.getOrNull()
        if (bundle == null) {
            Toast.makeText(context, R.string.diagnostics_share_failed, Toast.LENGTH_LONG).show()
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".files",
                bundle
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_diagnostics))
                putExtra(
                    Intent.EXTRA_TEXT,
                    buildReport(context, message).take(8000)
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                send,
                context.getString(R.string.share_diagnostics)
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
            true
        }.onFailure {
            Toast.makeText(context, R.string.diagnostics_share_failed, Toast.LENGTH_LONG).show()
        }.getOrDefault(false)
    }

    private fun buildReport(context: Context, message: String?): String = buildString {
        appendLine("=== Ruffle diagnostics ===")
        appendLine("time: ${timestamp()}")
        append(deviceInfo(context))
        if (!message.isNullOrBlank()) {
            appendLine("--- error ---")
            appendLine(message.take(12_000))
        }
    }

    private fun createBundle(context: Context, message: String?): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        // Keep only the newest bundle.
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(
            dir,
            "ruffle-diagnostics-${System.currentTimeMillis()}.zip"
        )
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { output ->
            fun addText(name: String, text: String) {
                output.putNextEntry(ZipEntry(name))
                output.write(text.toByteArray())
                output.closeEntry()
            }

            fun addFile(name: String, file: File, maxBytes: Long = MAX_LOG_BYTES.toLong()) {
                if (!file.isFile) return
                output.putNextEntry(ZipEntry(name))
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var remaining = maxBytes
                    while (remaining > 0) {
                        val read = input.read(
                            buffer,
                            0,
                            minOf(buffer.size.toLong(), remaining).toInt()
                        )
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                output.closeEntry()
            }

            addText("device-info.txt", buildReport(context, message))
            val storage = storageDir(context)
            addFile(PERF_LOG, File(storage, PERF_LOG))
            addFile(PERF_PREVIOUS, File(storage, PERF_PREVIOUS))
            addFile(CRASH_RUST, File(storage, CRASH_RUST))
            addFile(CRASH_JAVA, File(storage, CRASH_JAVA))
            addFile(CRASH_NATIVE, File(storage, CRASH_NATIVE))
            addFile(
                CRASH_NATIVE_TRACE,
                File(storage, CRASH_NATIVE_TRACE),
                Long.MAX_VALUE
            )
        }
        return zip
    }

    private fun tail(file: File, maxChars: Int): String {
        if (!file.isFile) return ""
        return runCatching {
            val length = file.length()
            val start = (length - maxChars).coerceAtLeast(0)
            file.inputStream().use { input ->
                input.skip(start)
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun timestamp(): String = formatTime(System.currentTimeMillis())

    private fun formatTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
}
