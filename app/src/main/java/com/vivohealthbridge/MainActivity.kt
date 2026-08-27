package com.vivohealthbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.vivohealthbridge.ui.navigation.AppNavigation
import com.vivohealthbridge.ui.theme.VivoHealthBridgeTheme
import com.vivohealthbridge.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        viewModel.onPermissionsResult(grantedPermissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VivoHealthBridgeTheme {
                AppNavigation(
                    viewModel = viewModel,
                    onRequestPermissions = { requestHealthConnectPermissions() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkStatus()
    }

    /**
     * The permission contract silently does nothing when Health Connect is
     * missing or too old (common on OEM builds that predate the Play Store
     * unbundling), so the SDK status is checked first and every failure path
     * lands the user somewhere they can actually act.
     */
    private fun requestHealthConnectPermissions() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE ->
                requestPermissionsLauncher.launch(viewModel.getPermissions())

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Toast.makeText(
                    this,
                    "Health Connect needs an update — opening the Play Store.",
                    Toast.LENGTH_LONG
                ).show()
                openPlayStoreHealthConnect()
            }

            else -> {
                Toast.makeText(
                    this,
                    "Health Connect is not installed — opening its Play Store page.",
                    Toast.LENGTH_LONG
                ).show()
                openPlayStoreHealthConnect()
            }
        }
    }

    private fun openPlayStoreHealthConnect() {
        val playStoreUri = Uri.parse(PLAY_STORE_URL)
        val intent = Intent(Intent.ACTION_VIEW, playStoreUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // No Play Store on the device (rare) — at least explain the situation.
            Toast.makeText(this, "Could not open the Play Store: ${e.message}", Toast.LENGTH_LONG)
                .show()
        }
    }

    private companion object {
        const val PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
    }
}