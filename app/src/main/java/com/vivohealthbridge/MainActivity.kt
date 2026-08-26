package com.vivohealthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
                    onRequestPermissions = {
                        requestPermissionsLauncher.launch(viewModel.getPermissions())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkStatus()
    }
}
