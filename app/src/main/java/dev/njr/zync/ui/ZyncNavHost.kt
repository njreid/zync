package dev.njr.zync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import dev.njr.zync.domain.NodeRepository

object Screen {
    const val INBOX = "inbox"
    const val TREE = "tree"
    const val CONTEXTS = "contexts"
    const val NODE = "node/{nodeId}"
    fun nodeRoute(id: Long) = "node/$id"
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun ZyncNavHost(repository: NodeRepository) {
    val navController = rememberNavController()
    val tabs = listOf(
        Tab(Screen.INBOX, "Inbox", Icons.Filled.Inbox),
        Tab(Screen.TREE, "Tree", Icons.Filled.Folder),
        Tab(Screen.CONTEXTS, "Contexts", Icons.Filled.Label),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(Screen.INBOX); launchSingleTop = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.INBOX,
            modifier = Modifier.padding(padding),
        ) {
            composable(Screen.INBOX) { Placeholder("Inbox") }
            composable(Screen.TREE) { Placeholder("Folders") }
            composable(Screen.CONTEXTS) { Placeholder("No contexts yet") }
            composable(
                Screen.NODE,
                arguments = listOf(navArgument("nodeId") { type = NavType.LongType }),
            ) { entry ->
                Placeholder("Node ${entry.arguments?.getLong("nodeId")}")
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
