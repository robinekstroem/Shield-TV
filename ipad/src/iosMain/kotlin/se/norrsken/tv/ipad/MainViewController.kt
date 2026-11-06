package se.norrsken.tv.ipad

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Swift-delen visar det här: hela appen, ritad med Compose. */
fun MainViewController(): UIViewController = ComposeUIViewController { IpadApp() }
