package app.voidfiles.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Node
import app.voidfiles.data.SafNode
import java.io.File

/** Hands files to other apps: open, share and "send to Proton Drive". */
class Launcher(private val context: Context) {

    fun uriFor(node: Node): Uri = when (node) {
        is LocalNode -> FileProvider.getUriForFile(context, context.packageName + ".files", node.file)
        is SafNode -> node.uri
    }

    fun open(node: Node, chooser: Boolean = false) {
        val mime = if (chooser) "*/*" else node.mimeType.takeUnless { it == "application/octet-stream" } ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(node), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        start(if (chooser || mime == "*/*") Intent.createChooser(intent, "Öffnen mit") else intent)
    }

    private fun sendIntent(nodes: List<Node>): Intent {
        val uris = ArrayList(nodes.filterNot { it.isDirectory }.map { uriFor(it) })
        val mimes = nodes.map { it.mimeType }.distinct()
        val mime = if (mimes.size == 1) mimes.first() else "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = mime
        val clip = ClipData.newRawUri(null, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    fun share(nodes: List<Node>) {
        if (nodes.none { !it.isDirectory }) {
            toast("Ordner können nicht geteilt werden – vorher als ZIP komprimieren"); return
        }
        start(Intent.createChooser(sendIntent(nodes), "Teilen"))
    }

    val protonInstalled: Boolean
        get() = try {
            context.packageManager.getPackageInfo(PROTON_DRIVE, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Uploads files through the installed Proton Drive app's share target. */
    fun sendToProton(nodes: List<Node>) {
        if (nodes.none { !it.isDirectory }) {
            toast("Ordner bitte zuerst als ZIP komprimieren"); return
        }
        if (!protonInstalled) {
            toast("Proton Drive ist nicht installiert"); return
        }
        val intent = sendIntent(nodes).setPackage(PROTON_DRIVE)
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            start(Intent.createChooser(sendIntent(nodes), "Hochladen mit"))
        }
    }

    fun shareText(subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        start(Intent.createChooser(intent, "Teilen"))
    }

    fun copyText(label: String, text: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Kopiert")
    }

    /** Hands a downloaded update APK to the system installer. */
    fun installApk(apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            toast("Bitte \"Unbekannte Apps installieren\" für VOID Files erlauben und dann erneut auf Aktualisieren tippen")
            start(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        start(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    /** Pins a home screen shortcut that opens [folder] directly. */
    fun pinFolderShortcut(folder: File) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            toast("Der Launcher unterstützt keine Verknüpfungen"); return
        }
        val intent = Intent(context, app.voidfiles.MainActivity::class.java)
            .setAction(ACTION_OPEN_FOLDER)
            .putExtra(EXTRA_PATH, folder.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val info = ShortcutInfoCompat.Builder(context, "folder:" + folder.absolutePath)
            .setShortLabel(folder.name.ifEmpty { "Speicher" })
            .setLongLabel("VOID · " + folder.name.ifEmpty { "Speicher" })
            .setIcon(IconCompat.createWithResource(context, app.voidfiles.R.mipmap.ic_shortcut_folder))
            .setIntent(intent)
            .build()
        if (!ShortcutManagerCompat.requestPinShortcut(context, info, null)) toast("Verknüpfung konnte nicht erstellt werden")
    }

    /**
     * Opens the drive in Samsung's "Eigene Dateien" (a system app that may unmount it via ⋮ → "Trennen").
     * Falls back to the plain My Files app and finally the storage settings.
     */
    fun openSystemEject(root: File) {
        val pm = context.packageManager
        val myFiles = listOf(SAMSUNG_MY_FILES).firstOrNull {
            runCatching { pm.getPackageInfo(it, 0) }.isSuccess
        }
        if (myFiles != null) {
            val atPath = Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
                .setPackage(myFiles)
                .putExtra("samsung.myfiles.intent.extra.START_PATH", root.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(atPath)
                return
            } catch (e: Exception) {
                // Older/newer My Files versions may not know this action – just open the app.
            }
            pm.getLaunchIntentForPackage(myFiles)?.let {
                try {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    toast("In \"Eigene Dateien\" den USB-Speicher öffnen, dann ⋮ → \"Trennen\"")
                    return
                } catch (e: Exception) {
                    // fall through to the settings
                }
            }
        }
        openStorageSettings()
    }

    /** System storage settings, where SD cards and USB drives can be ejected. */
    fun openStorageSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_MEMORY_CARD_SETTINGS),
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                continue
            }
        }
        toast("Speicher-Einstellungen nicht gefunden")
    }

    fun openProtonApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(PROTON_DRIVE)
        if (launch == null) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROTON_DRIVE")))
        } else {
            start(launch)
        }
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast("Keine passende App gefunden")
        } catch (e: Exception) {
            toast(e.message ?: "Fehler beim Öffnen")
        }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val PROTON_DRIVE = "me.proton.android.drive"
        const val SAMSUNG_MY_FILES = "com.sec.android.app.myfiles"
    }
}
