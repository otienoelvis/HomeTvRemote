package com.example.hometv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.hometv.data.repository.TvRemoteRepositoryImpl
import com.example.hometv.domain.model.ConnectionStatus
import com.example.hometv.ui.remote.RemoteScreen
import com.example.hometv.ui.remote.RemoteViewModel
import com.example.hometv.ui.theme.HomeTvTheme

class MainActivity : ComponentActivity() {

    private companion object {
        private const val TAG = "HomeTvLog"
    }

    private var repository: TvRemoteRepositoryImpl? = null
    private var viewModel: RemoteViewModel? = null

    private val nearbyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "Nearby Wi-Fi permission granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        try {
            val repo = TvRemoteRepositoryImpl(applicationContext)
            val vm = RemoteViewModel(repo)
            repository = repo
            viewModel = vm
            Log.d(TAG, "Repository and ViewModel initialized")

            setContent {
                HomeTvTheme {
                    RemoteScreen(viewModel = vm)
                }
            }
            requestNearbyPermission()
        } catch (e: Exception) {
            Log.e(TAG, "Could not initialize HomeTv", e)
        }
    }

    private fun requestNearbyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity onStart, starting scan")
        try {
            if (viewModel?.uiState?.value?.connectionStatus != ConnectionStatus.CONNECTED) {
                viewModel?.startScan()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "MainActivity onStop, stopping scan")
        try {
            viewModel?.stopScan()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy")
        repository?.close()
    }
}
