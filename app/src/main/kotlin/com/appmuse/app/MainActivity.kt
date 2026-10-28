package com.appmuse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.appmuse.app.ui.AppNav
import com.appmuse.app.ui.theme.AppmuseTheme

/** Local-only entry point: no authentication, guest session, or backend bootstrap. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppmuseTheme {
                AppNav()
            }
        }
    }
}
