package app.minispot.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.minispot.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

const val REDIRECT_URI = "minispot://callback"

private val SCOPES = listOf(
    "user-read-private",
    "user-read-recently-played",
    "user-follow-read",
    "user-library-read",
    "user-library-modify",
    "app-remote-control",
).joinToString(" ")

class AuthException(message: String) : Exception(message)

/** Authorization Code + PKCE against accounts.spotify.com. No client secret, no backend. */
class SpotifyAuth(private val prefs: Prefs) {
    private val mutex = Mutex()

    private val _loggedIn = MutableStateFlow(prefs.refreshToken != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    fun startLogin(context: Context) {
        val verifier = randomString(64)
        prefs.pkceVerifier = verifier
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", prefs.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Returns null on success, or an error message. */
    suspend fun handleRedirect(uri: Uri): String? {
        uri.getQueryParameter("error")?.let { return it }
        val code = uri.getQueryParameter("code") ?: return "no code in redirect"
        val verifier = prefs.pkceVerifier ?: return "login was not started from this app"
        return try {
            tokenRequest(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to prefs.clientId,
                    "code_verifier" to verifier,
                )
            )
            prefs.pkceVerifier = null
            _loggedIn.value = true
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    suspend fun accessToken(forceRefresh: Boolean = false): String = mutex.withLock {
        val current = prefs.accessToken
        if (!forceRefresh && current != null && System.currentTimeMillis() < prefs.tokenExpiresAt - 60_000) {
            return current
        }
        val refresh = prefs.refreshToken ?: run {
            _loggedIn.value = false
            throw AuthException("not logged in")
        }
        try {
            tokenRequest(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to prefs.clientId,
                )
            )
        } catch (e: AuthException) {
            // Refresh tokens now expire after six months; the user has to log in again.
            logout()
            throw e
        }
        prefs.accessToken!!
    }

    fun logout() {
        prefs.clearAuth()
        _loggedIn.value = false
    }

    private suspend fun tokenRequest(params: Map<String, String>) = withContext(Dispatchers.IO) {
        val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 400 || code == 401) throw AuthException("token request failed: $text")
        if (code !in 200..299) throw Exception("token request failed ($code): $text")
        val json = JSONObject(text)
        prefs.accessToken = json.getString("access_token")
        prefs.tokenExpiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { prefs.refreshToken = it }
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}
