package app.voidfiles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.voidfiles.ui.DotGrid
import app.voidfiles.ui.Label
import app.voidfiles.ui.MainScreen
import app.voidfiles.ui.MainViewModel
import app.voidfiles.ui.theme.VoidFilesTheme
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasAccess = checkAccess()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            VoidFilesTheme(settings.theme, settings.accent, settings.dotHeadings, settings.dotGrid) {
                if (hasAccess) {
                    MainScreen(vm, settings)
                } else {
                    PermissionScreen(onGrant = ::requestAccess)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val now = checkAccess()
        if (now && !hasAccess) {
            vm.reload(vm.left)
            vm.reload(vm.right)
        }
        hasAccess = now
    }

    private fun checkAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private val legacyPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResume() }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    val c = VoidTheme.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        DotGrid(Modifier.fillMaxSize(), spacing = 22.dp)
        Column(Modifier.safeDrawingPadding().padding(28.dp).align(Alignment.BottomStart)) {
            Row {
                Text("VOID", style = headingStyle(72), color = c.text)
                Text(".", style = headingStyle(72), color = c.accent)
            }
            Label("Files")
            Spacer(Modifier.height(28.dp))
            Text(
                "Um deine Dateien zu verwalten, braucht VOID Files Zugriff auf alle Dateien. " +
                    "Aktiviere im nächsten Bildschirm \"Zugriff auf alle Dateien erlauben\".",
                color = c.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(c.accent).clickable(onClick = onGrant)
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            ) {
                Text("ZUGRIFF ERLAUBEN", style = MaterialTheme.typography.labelLarge, color = c.onAccent)
            }
        }
    }
}
