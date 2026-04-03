package com.example.mytts

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.mytts.audio.PlaybackController
import com.example.mytts.service.PlaybackService
import com.example.mytts.ui.MainScreen
import com.example.mytts.ui.theme.MyTTSTheme
import com.example.mytts.util.PreferencesManager
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private var playbackController: PlaybackController? = null
    private lateinit var prefs: PreferencesManager
    private var bound = mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as PlaybackService.LocalBinder).service
            playbackController = service.playbackController
            bound.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackController = null
            bound.value = false
        }
    }

    // Request notification permission on Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, we proceed either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferencesManager(applicationContext)

        // Use dark status bar icons so they're visible on our white background
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start and bind to PlaybackService
        PlaybackService.start(this)
        bindService(
            Intent(this, PlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )

        setContent {
            MyTTSTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val isBound by bound

                    if (isBound && playbackController != null) {
                        // Observe playback state to update notification
                        val state by playbackController!!.state.collectAsState()
                        LaunchedEffect(state) {
                            val isPlaying = state == PlaybackController.State.PLAYING
                            // Update foreground service notification
                            val intent = Intent(this@MainActivity, PlaybackService::class.java)
                            bindService(intent, object : ServiceConnection {
                                override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
                                    (b as? PlaybackService.LocalBinder)?.service
                                        ?.updateNotification(isPlaying)
                                }
                                override fun onServiceDisconnected(n: ComponentName?) {}
                            }, Context.BIND_AUTO_CREATE)
                        }

                        MainScreen(
                            controller = playbackController!!,
                            prefs = prefs
                        )
                    } else {
                        // Loading state while service binds
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (bound.value) {
            unbindService(connection)
            bound.value = false
        }
        // Only stop service if playback is not active
        val ctrl = playbackController
        if (ctrl == null || ctrl.state.value == PlaybackController.State.STOPPED) {
            PlaybackService.stop(this)
        }
        super.onDestroy()
    }
}
