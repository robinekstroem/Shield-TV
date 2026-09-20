package se.norrsken.tv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import se.norrsken.tv.core.ui.theme.NorrskenTheme
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.SettingsRepository
import se.norrsken.tv.ui.shell.AppShell
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val preferences = settings.preferences
            .stateIn(lifecycleScope, SharingStarted.Eagerly, AppPreferences())

        onBackPressedDispatcher.addCallback(this) {
            // Tva tryck inom tva sekunder. Pa en fjarrkontroll ar Back den
            // knapp som trycks av misstag oftast, och att kastas ur en
            // sandning av ett felslag ar irriterande.
            if (!preferences.value.confirmExit) {
                finish()
                return@addCallback
            }
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2_000) {
                finish()
            } else {
                lastBackAt = now
                Toast.makeText(
                    this@MainActivity,
                    "Tryck igen for att avsluta",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        setContent {
            val prefs by preferences.collectAsState()
            NorrskenTheme(preferences = prefs) {
                AppShell()
            }
        }
    }
}
