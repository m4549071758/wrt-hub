package wrthub.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import wrthub.app.AppContainer
import wrthub.app.R
import wrthub.app.ui.clients.ClientsRoute
import wrthub.app.ui.clients.ClientsViewModel
import wrthub.app.ui.home.HomeRoute
import wrthub.app.ui.home.HomeViewModel
import wrthub.app.ui.mesh.MeshRoute
import wrthub.app.ui.mesh.MeshViewModel
import wrthub.app.ui.sites.AddNodeRoute
import wrthub.app.ui.sites.AddNodeViewModel
import wrthub.app.ui.sites.SitesRoute
import wrthub.app.ui.sites.SitesViewModel

@Serializable
data object HomeDestination

@Serializable
data object ClientsDestination

@Serializable
data object MeshDestination

@Serializable
data object SitesDestination

@Serializable
data class AddNodeDestination(val siteId: String? = null)

@Composable
fun WrtHubApp(container: AppContainer) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (destination?.hasRoute<AddNodeDestination>() != true) {
                NavigationBar {
                    NavigationBarItem(
                        selected = destination?.hasRoute<HomeDestination>() == true,
                        onClick = { navController.switchTab(HomeDestination) },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_home)) },
                    )
                    NavigationBarItem(
                        selected = destination?.hasRoute<ClientsDestination>() == true,
                        onClick = { navController.switchTab(ClientsDestination) },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_clients)) },
                    )
                    NavigationBarItem(
                        selected = destination?.hasRoute<MeshDestination>() == true,
                        onClick = { navController.switchTab(MeshDestination) },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_mesh)) },
                    )
                    NavigationBarItem(
                        selected = destination?.hasRoute<SitesDestination>() == true,
                        onClick = { navController.switchTab(SitesDestination) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_sites)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = HomeDestination, modifier = Modifier.padding(padding)) {
            composable<HomeDestination> {
                val vm: HomeViewModel = viewModel(
                    factory = viewModelFactory { initializer { HomeViewModel(container.session, container.counters) } },
                )
                HomeRoute(vm, onAddSite = { navController.navigate(AddNodeDestination()) })
            }
            composable<ClientsDestination> {
                val vm: ClientsViewModel = viewModel(
                    factory = viewModelFactory { initializer { ClientsViewModel(container.session, container.blockingFor) } },
                )
                ClientsRoute(vm)
            }
            composable<MeshDestination> {
                val vm: MeshViewModel = viewModel(factory = viewModelFactory { initializer { MeshViewModel(container.session) } })
                MeshRoute(vm)
            }
            composable<SitesDestination> {
                val vm: SitesViewModel = viewModel(factory = viewModelFactory { initializer { SitesViewModel(container.siteStore) } })
                SitesRoute(
                    vm,
                    onAddSite = { navController.navigate(AddNodeDestination()) },
                    onAddNode = { navController.navigate(AddNodeDestination(it)) },
                )
            }
            composable<AddNodeDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<AddNodeDestination>()
                val vm: AddNodeViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AddNodeViewModel(route.siteId, container.siteStore, container.verifier, container.cipher) }
                    },
                )
                AddNodeRoute(vm, onDone = { navController.popBackStack() }, onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
