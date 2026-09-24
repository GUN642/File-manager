package app.voidfiles.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Node
import app.voidfiles.data.SafNode

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
    }
}
