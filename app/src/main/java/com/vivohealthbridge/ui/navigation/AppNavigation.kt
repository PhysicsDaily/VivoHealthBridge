package com.vivohealthbridge.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vivohealthbridge.ui.screens.DashboardScreen
import com.vivohealthbridge.ui.screens.HistoryScreen
import com.vivohealthbridge.ui.screens.ManualEntryScreen
import com.vivohealthbridge.viewmodel.MainViewModel

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Filled.Home)
    object ManualEntry : Screen("manual_entry", "Manual Entry", Icons.Filled.Add)
    object History : Screen("history", "History", Icons.Filled.List)
}

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()
    val items = listOf(
        Screen.Dashboard,
        Screen.ManualEntry,
        Screen.History
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    viewModel = viewModel,
                    onRequestPermissions = onRequestPermissions,
                    onNavigateToManualEntry = {
                        navController.navigate(Screen.ManualEntry.route)
                    }
                )
            }
            composable(Screen.ManualEntry.route) {
                ManualEntryScreen(viewModel = viewModel)
            }
            composable(Screen.History.route) {
                HistoryScreen(viewModel = viewModel)
            }
        }
    }
}
