package se.norrsken.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import se.norrsken.tv.core.ui.theme.NorrskenTheme
import se.norrsken.tv.ui.shell.AppShell

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NorrskenTheme {
                AppShell()
            }
        }
    }
}
