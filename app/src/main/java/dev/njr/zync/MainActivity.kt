package dev.njr.zync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.njr.zync.theme.ZyncTheme
import dev.njr.zync.ui.ZyncNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZyncTheme {
                ZyncNavHost(repository = (application as ZyncApp).repository)
            }
        }
    }
}
