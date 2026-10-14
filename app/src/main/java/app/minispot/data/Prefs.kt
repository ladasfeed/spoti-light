package app.minispot.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small key-value store for settings and tokens. Personal app, so plain SharedPreferences is enough. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("minispot", Context.MODE_PRIVATE)

    var clientId: String
        get() = sp.getString("client_id", null) ?: DEFAULT_CLIENT_ID
        set(v) = sp.edit().putString("client_id", v.trim()).apply()

    var accessToken: String?
        get() = sp.getString("access_token", null)
        set(v) = sp.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = sp.getString("refresh_token", null)
        set(v) = sp.edit().putString("refresh_token", v).apply()

    var tokenExpiresAt: Long
        get() = sp.getLong("token_expires_at", 0L)
        set(v) = sp.edit().putLong("token_expires_at", v).apply()

    /** Scopes requested at the last successful login. */
    var grantedScopes: String?
        get() = sp.getString("granted_scopes", null)
        set(v) = sp.edit().putString("granted_scopes", v).apply()

    var pkceVerifier: String?
        get() = sp.getString("pkce_verifier", null)
        set(v) = sp.edit().putString("pkce_verifier", v).apply()

    var userId: String?
        get() = sp.getString("user_id", null)
        set(v) = sp.edit().putString("user_id", v).apply()

    /** Optional fallback: link to the user's daylist copied from the Spotify app. */
    var daylistLink: String
        get() = sp.getString("daylist_link", "") ?: ""
        set(v) = sp.edit().putString("daylist_link", v.trim()).apply()

    private val _theme = MutableStateFlow(sp.getString("theme", "spotify") ?: "spotify")
    val theme: StateFlow<String> = _theme

    fun setTheme(id: String) {
        sp.edit().putString("theme", id).apply()
        _theme.value = id
    }

    /** Which hand holds the phone ("left" or "right"); the home screen lines up on that side. */
    private val _hand = MutableStateFlow(sp.getString("hand", "right") ?: "right")
    val hand: StateFlow<String> = _hand

    fun setHand(value: String) {
        sp.edit().putString("hand", value).apply()
        _hand.value = value
    }

    /** Text size multiplier on top of the system one. */
    private val _fontScale = MutableStateFlow(sp.getFloat("font_scale", 1f))
    val fontScale: StateFlow<Float> = _fontScale

    fun setFontScale(value: Float) {
        sp.edit().putFloat("font_scale", value).apply()
        _fontScale.value = value
    }

    private val _font = MutableStateFlow(sp.getString("font", "roboto") ?: "roboto")
    val font: StateFlow<String> = _font

    fun setFont(id: String) {
        sp.edit().putString("font", id).apply()
        _font.value = id
    }

    /** Home panel height in dp; negative means the default. */
    var homePanelHeight: Float
        get() = sp.getFloat("home_panel_height", -1f)
        set(v) = sp.edit().putFloat("home_panel_height", v).apply()

    /** Gap between the home panel and the bottom of the screen, in dp. */
    var homePanelBottom: Float
        get() = sp.getFloat("home_panel_bottom", 0f)
        set(v) = sp.edit().putFloat("home_panel_bottom", v).apply()

    fun clearAuth() {
        sp.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("token_expires_at")
            .remove("user_id")
            .apply()
    }
}

/** Client ID of the "Lightspot" app in the Spotify developer dashboard. Not a secret (PKCE flow). */
const val DEFAULT_CLIENT_ID = "77250c2f983c4f3ab90c0e8135d92d58"
