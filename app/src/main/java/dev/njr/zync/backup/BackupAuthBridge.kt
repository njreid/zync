package dev.njr.zync.backup

import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

class BackupAuthBridge(
    private val webView: WebView,
    private val launchSignIn: (Intent) -> Unit,
) {
    @JavascriptInterface
    fun connectGoogleDrive() {
        webView.post {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DRIVE_APPDATA_SCOPE))
                .build()
            launchSignIn(GoogleSignIn.getClient(webView.context, options).signInIntent)
        }
    }

    fun handleSignInResult(data: Intent?) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val script = if (task.isSuccessful) {
            val email = task.result?.email.orEmpty()
            "window.__zyncGoogleDriveResult && window.__zyncGoogleDriveResult(${jsString(email)}, null)"
        } else {
            val message = task.exception?.message ?: "Google Drive authorization failed"
            "window.__zyncGoogleDriveResult && window.__zyncGoogleDriveResult(null, ${jsString(message)})"
        }
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun jsString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

}
