package app.voidfiles

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

/** Writes uncaught crashes to a file so the next start can offer to share the report. */
object CrashReporter {
    private fun file(context: Context) = File(context.filesDir, "crash/last.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN).format(Date())
        f.writeText(
            buildString {
                appendLine("VOID Files ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Zeit: $time")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(error.stackTraceToString())
            },
        )
    }

    /** The report of the last crash, if there is one that was not dismissed yet. */
    fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }
}
