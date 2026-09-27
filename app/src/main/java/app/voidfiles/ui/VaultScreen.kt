package app.voidfiles.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.voidfiles.data.LocalNode
import app.voidfiles.data.VaultEntry
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle
import java.io.File

/** Asks for fingerprint / face / screen lock before [onSuccess] runs. */
fun authenticate(activity: FragmentActivity, title: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK
    val can = BiometricManager.from(activity).canAuthenticate(authenticators)
    if (can != BiometricManager.BIOMETRIC_SUCCESS) {
        onError("Bitte zuerst eine Displaysperre oder einen Fingerabdruck einrichten")
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) onError(errString.toString())
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle("VOID Files")
        .setAllowedAuthenticators(authenticators)
        .apply { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) setNegativeButtonText("Abbrechen") }
        .build()
    prompt.authenticate(info)
}

@Composable
fun VaultScreen(vm: MainViewModel) {
    val c = VoidTheme.colors
    var confirmDelete by remember { mutableStateOf<VaultEntry?>(null) }
    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.lockVault() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = c.text) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.lockVault() }) { Icon(Icons.Outlined.Lock, "Sperren", tint = c.accent) }
        }
        Text("TRESOR", style = headingStyle(40), color = c.text, modifier = Modifier.padding(horizontal = 20.dp))
        Label(
            "${vm.vaultEntries.size} Dateien · AES-256 verschlüsselt · Wiederherstellen nach: ${vm.active.current.name}",
            Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
        )
        if (vm.vaultEntries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (VoidTheme.dotGrid) DotGrid(Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("LEER", style = headingStyle(34), color = c.textMuted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Dateien auswählen und im ⋮-Menü \"In Tresor verschieben\" wählen.",
                        color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(vm.vaultEntries, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.vaultOpen(e) }.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Icon only from the name – the content stays encrypted.
                        NodeIcon(LocalNode(File(e.name), e.name, false, e.size, e.added), 40.dp, false)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${formatSize(e.size)}  ·  ${formatDate(e.added)}", style = MaterialTheme.typography.labelSmall,
                                color = c.textMuted, maxLines = 1,
                            )
                        }
                        IconButton(onClick = { vm.vaultRestore(e) }) { Icon(Icons.Outlined.LockOpen, "Wiederherstellen", tint = c.text) }
                        IconButton(onClick = { confirmDelete = e }) { Icon(Icons.Outlined.DeleteForever, "Löschen", tint = c.accent) }
                    }
                }
            }
        }
    }
    confirmDelete?.let { e ->
        ConfirmDialog("Löschen", "\"${e.name}\" endgültig aus dem Tresor löschen?", "Löschen", { confirmDelete = null }) {
            confirmDelete = null
            vm.vaultDelete(e)
        }
    }
}
