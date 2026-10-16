package com.scalfy.app

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import android.os.Bundle

class MainActivity : ComponentActivity() {
    private lateinit var nfc: NfcTapManager
    private val transfer get() = TapController.transfer
    private var askedPerms = false
    private var askedOverlay = false
    private var awaitingOverlay = false
    private var nfcOk by mutableStateOf(true)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { transfer.reset() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc = NfcTapManager(this)
        setContent {
            ScalfyTheme { TapExperience { Screen() } }
        }
    }

    override fun onResume() {
        super.onResume()
        ScalfyState.appVisible = true
        nfcOk = NfcAdapter.getDefaultAdapter(this)?.isEnabled == true
        nfc.start()
        val missing = Setup.missing(this, Setup.btPerms())
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
        else if (ScalfyState.phase.value == Phase.IDLE) transfer.reset()
        if (awaitingOverlay) { awaitingOverlay = false; enableBackground() }
        else if (intent.getBooleanExtra("setup", false)) { intent.removeExtra("setup"); enableBackground() }
    }

    override fun onPause() {
        super.onPause()
        ScalfyState.appVisible = false
        nfc.stop()
        if (!ScalfyState.serviceOn.value) transfer.stop()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }

    private val bgPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { enableBackground() }

    /** Permissions -> overlay permission -> start the background service. */
    private fun enableBackground() {
        val ask = Setup.missing(this)
        if (ask.isNotEmpty() && !askedPerms) { askedPerms = true; bgPerms.launch(ask.toTypedArray()); return }
        askedPerms = false
        if (Setup.missing(this, Setup.btPerms()).isNotEmpty()) {
            Toast.makeText(this, "Bluetooth / Nearby permission is needed", Toast.LENGTH_LONG).show(); return
        }
        if (!Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true; awaitingOverlay = true
            Toast.makeText(this, "Allow \"Display over other apps\" for Scalfy", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        askedOverlay = false
        if (!Settings.canDrawOverlays(this))
            Toast.makeText(this, "Glow overlay is off. Taps still work.", Toast.LENGTH_LONG).show()
        try { startForegroundService(Intent(this, ShareService::class.java)) }
        catch (_: Exception) { Toast.makeText(this, "Couldn't start background mode", Toast.LENGTH_SHORT).show() }
    }

    private fun disableBackground() {
        startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP))
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, ScalfyTileService::class.java), "Scalfy",
                Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor) { }
        } else Toast.makeText(this, "Swipe down twice, tap the pencil, drag Scalfy into your tiles", Toast.LENGTH_LONG).show()
    }

    @Composable
    private fun Screen() {
        val ctx = this
        var dialog by remember { mutableStateOf<String?>(null) }

        val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            addUris(uris, Kind.PHOTO)
        }
        val files = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> addUris(uris, Kind.FILE) }
        val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri -> uri?.let { readContact(it) } }
        val contactPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) pickContact.launch(null) }

        Home(
            nfcOk = nfcOk,
            onNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
            onPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onFiles = { files.launch("*/*") },
            onContact = {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) pickContact.launch(null)
                else contactPerm.launch(Manifest.permission.READ_CONTACTS)
            },
            onText = { dialog = "text" },
            onEditName = { dialog = "name" },
            onOpen = { openItem(it) },
            onToggleBg = { on -> if (on) enableBackground() else disableBackground() },
            onAddTile = { addTile() }
        )

        dialog?.let { kind ->
            var value by remember { mutableStateOf(if (kind == "name") ScalfyState.myName.value else "") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (kind == "name") "Your name" else "Text to share") },
                text = { OutlinedTextField(value, { value = it }, singleLine = kind == "name") },
                confirmButton = {
                    TextButton({
                        if (value.isNotBlank()) {
                            if (kind == "name") ScalfyState.saveName(ctx, value)
                            else ScalfyState.outbox.value += ShareItem(Kind.TEXT, value.take(40), text = value)
                        }
                        dialog = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } }
            )
        }
    }

    private fun addUris(uris: List<Uri>, kind: Kind) {
        val items = uris.map { u ->
            val mime = contentResolver.getType(u)
            val name = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "file_${System.nanoTime()}"
            ShareItem(if (mime?.startsWith("image/") == true || mime?.startsWith("video/") == true) Kind.PHOTO else kind, name, uri = u, mime = mime)
        }
        ScalfyState.outbox.value += items
    }

    private fun readContact(uri: Uri) {
        try {
            val key = contentResolver.query(uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return
            val v = contentResolver.openInputStream(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, key))
                ?.bufferedReader()?.use { it.readText() } ?: return
            ScalfyState.outbox.value += ShareItem(Kind.CONTACT, vField(v, "FN") ?: "Contact", text = v)
        } catch (_: Exception) { Toast.makeText(this, "Couldn't read contact", Toast.LENGTH_SHORT).show() }
    }

    private fun vField(v: String, key: String) =
        v.lineSequence().firstOrNull { it.startsWith(key, true) }?.substringAfter(':')?.trim()

    private fun openItem(i: ShareItem) {
        try {
            when (i.kind) {
                Kind.PHOTO, Kind.FILE -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(i.uri, i.mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                Kind.CONTACT -> startActivity(Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.NAME, i.title)
                    vField(i.text ?: "", "TEL")?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                    vField(i.text ?: "", "EMAIL")?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                })
                Kind.TEXT -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Scalfy", i.text ?: ""))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) { Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show() }
    }
}
