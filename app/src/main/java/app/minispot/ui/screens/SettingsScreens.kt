package app.minispot.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minispot.MinispotApp
import app.minispot.auth.REDIRECT_URI
import app.minispot.ui.components.SectionHeader
import app.minispot.ui.components.TopBar
import app.minispot.ui.theme.Themes

/** SHA1 of app/keystore/minispot.jks, which signs every build. Spotify needs it for App Remote. */
const val SIGNING_SHA1 = "EC:61:11:EF:5E:E8:2F:5C:4E:7B:BB:84:29:B8:37:5C:BC:EC:8A:AB"
const val PACKAGE_NAME = "app.minispot"

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = MinispotApp.instance
    val theme by app.prefs.theme.collectAsState()
    var daylist by remember { mutableStateOf(app.prefs.daylistLink) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar("Settings", onBack)

        SectionHeader("Theme")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Themes.forEach { t ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(t.background)
                        .border(
                            width = if (t.id == theme) 2.dp else 1.dp,
                            color = if (t.id == theme) t.accent else t.surfaceHigh,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { app.prefs.setTheme(t.id) }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(t.accent)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(t.title, color = t.text, fontSize = 12.sp)
                }
            }
        }

        SectionHeader("Daylist")
        Text(
            "If Made for you can't find your daylist, copy its link in Spotify (Share → Copy link) and paste it here.",
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        OutlinedTextField(
            value = daylist,
            onValueChange = {
                daylist = it
                app.prefs.daylistLink = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("https://open.spotify.com/playlist/…") },
            singleLine = true,
        )

        SectionHeader("Account")
        OutlinedButton(
            onClick = { app.player.disconnect(); app.auth.logout() },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { Text("Log out") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun SetupScreen(error: String?) {
    val app = MinispotApp.instance
    val context = LocalContext.current
    var clientId by remember { mutableStateOf(app.prefs.clientId) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Minispot", fontSize = 36.sp, fontWeight = FontWeight.Black)
        Text(
            "Create an app once at developer.spotify.com/dashboard (Premium required) with these settings:",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CopyRow(context, "Redirect URI", REDIRECT_URI)
        CopyRow(context, "Android package", PACKAGE_NAME)
        CopyRow(context, "SHA1 fingerprint", SIGNING_SHA1)
        Text(
            "Enable Web API and Android, then paste the app's Client ID here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                app.prefs.clientId = clientId
                app.auth.startLogin(context)
            },
            enabled = clientId.trim().length >= 16,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Log in with Spotify") }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CopyRow(context: Context, label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .padding(12.dp),
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}
