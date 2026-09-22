package se.norrsken.tv

import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import se.norrsken.tv.core.ui.theme.NorrskenTheme
import se.norrsken.tv.data.epg.EpgCoordinator
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.EffectLevel
import se.norrsken.tv.data.settings.SettingsRepository
import se.norrsken.tv.ui.shell.AppShell
import se.norrsken.tv.ui.shell.SplashOverlay
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var epg: EpgCoordinator

    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Tablån hämtas i bakgrunden när den blivit gammal. Appen väntar
        // aldrig på den — hemskärmen visas direkt med det som redan finns.
        //
        // Två tillfällen, för att "vid appstart" inte räcker: på en TV-box
        // startas appen sällan om. Man går till hemskärmen och kommer
        // tillbaka, eller lämnar den öppen i dagar.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) epg.updateIfStale()
            }
        )
        lifecycleScope.launch {
            while (true) {
                epg.updateIfStale()
                delay(60 * 60 * 1000L)
            }
        }

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
                    "Tryck igen för att avsluta",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // Systemets "Ta bort animationer". Respekteras fullt ut: ingen
        // startsekvens, inga övergångar, inga partiklar.
        val systemReducedMotion = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

        setContent {
            val prefs by preferences.collectAsState()
            // Effekter "Av" är prestandaläget: det stänger av rörelse också.
            val reducedMotion = systemReducedMotion || prefs.effects == EffectLevel.OFF
            // rememberSaveable: sekvensen visas vid kallstart, inte när
            // skärmen vrids eller appen kommer tillbaka från bakgrunden.
            var showSplash by rememberSaveable { mutableStateOf(!systemReducedMotion) }

            NorrskenTheme(preferences = prefs, reducedMotion = reducedMotion) {
                Box(Modifier.fillMaxSize()) {
                    AppShell()
                    if (showSplash) {
                        SplashOverlay(onFinished = { showSplash = false })
                    }
                }
            }
        }
    }
}
