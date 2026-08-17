package com.gios.brightimport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.gios.brightimport.ui.SyncScreen
import com.gios.brightimport.ui.SyncViewModel
import com.gios.brightimport.ui.theme.BrightImportTheme

class MainActivity : ComponentActivity() {

    private val vm: SyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BrightImportTheme {
                Surface(Modifier.fillMaxSize().background(Color.Black)) {
                    SyncScreen(vm)
                }
            }
        }
    }

    /**
     * Hand the network back when the app leaves the foreground.
     *
     * A process still bound to a camera access point has no working network at all, and the camera
     * powers its Wi-Fi down after a couple of idle minutes regardless. Holding the binding past
     * this screen breaks every other app on the phone, which is a far worse bug than losing a
     * connection the user walked away from.
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing) vm.disconnect()
    }
}
