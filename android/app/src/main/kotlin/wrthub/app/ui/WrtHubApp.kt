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
import wrthub.app.ui.admin.ForwardsRoute
import wrthub.app.ui.admin.ForwardsViewModel
import wrthub.app.ui.admin.LeasePrefill
import wrthub.app.ui.admin.LeasesRoute
import wrthub.app.ui.admin.LeasesViewModel
import wrthub.app.ui.admin.StatusRoute
import wrthub.app.ui.admin.StatusViewModel
import wrthub.app.ui.admin.SystemRoute
import wrthub.app.ui.admin.SystemViewModel
import wrthub.app.ui.admin.WanRoute
import wrthub.app.ui.admin.WanViewModel
import wrthub.app.ui.admin.WirelessRoute
import wrthub.app.ui.admin.WirelessViewModel
import wrthub.app.ui.clients.ClientsRoute
import wrthub.app.ui.clients.ClientsViewModel
import wrthub.app.ui.home.HomeRoute
import wrthub.app.ui.home.HomeViewModel
import wrthub.app.ui.mesh.MeshRoute
import wrthub.app.ui.mesh.MeshViewModel
import wrthub.app.ui.node.NodeDetailRoute
import wrthub.app.ui.node.NodeDetailViewModel
import wrthub.app.ui.node.AdminPage
import wrthub.app.ui.sites.WifiRoute
import wrthub.app.ui.sites.WifiViewModel
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

@Serializable
data class NodeDestination(val nodeId: String)

@Serializable
data class WifiDestination(val siteId: String)

@Serializable
data class StatusDestination(val nodeId: String)

/** [mac] set: opened from a client to pin its IP. */
@Serializable
data class LeasesDestination(val nodeId: String, val mac: String? = null, val ip: String? = null, val name: String? = null)

@Serializable
data class ForwardsDestination(val nodeId: String)

@Serializable
data class WirelessDestination(val nodeId: String)

@Serializable
data class WanDestination(val nodeId: String)

@Serializable
data class SystemDestination(val nodeId: String)

@Composable
fun WrtHubApp(container: AppContainer) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val onTab = destination?.let {
                it.hasRoute<HomeDestination>() || it.hasRoute<ClientsDestination>() || it.hasRoute<MeshDestination>() || it.hasRoute<SitesDestination>()
            } != false
            if (onTab) {
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
                HomeRoute(
                    vm,
                    onAddSite = { navController.navigate(AddNodeDestination()) },
                    onOpenNode = { navController.navigate(NodeDestination(it)) },
                )
            }
            composable<ClientsDestination> {
                val vm: ClientsViewModel = viewModel(
                    factory = viewModelFactory { initializer { ClientsViewModel(container.session, container.operationsFor) } },
                )
                ClientsRoute(vm, onPinIp = { nodeId, client ->
                    navController.navigate(LeasesDestination(nodeId, client.mac, client.ipv4, client.name))
                })
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
                    onOpenWifi = { navController.navigate(WifiDestination(it)) },
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
            composable<NodeDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<NodeDestination>()
                val vm: NodeDetailViewModel = viewModel(
                    factory = viewModelFactory { initializer { NodeDetailViewModel(route.nodeId, container.session, container.operationsFor) } },
                )
                NodeDetailRoute(vm, onBack = { navController.popBackStack() }) { page ->
                    navController.navigate(
                        when (page) {
                            AdminPage.STATUS -> StatusDestination(route.nodeId)
                            AdminPage.LEASES -> LeasesDestination(route.nodeId)
                            AdminPage.FORWARDS -> ForwardsDestination(route.nodeId)
                            AdminPage.WIRELESS -> WirelessDestination(route.nodeId)
                            AdminPage.WAN -> WanDestination(route.nodeId)
                            AdminPage.SYSTEM -> SystemDestination(route.nodeId)
                        },
                    )
                }
            }
            composable<WifiDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<WifiDestination>()
                val vm: WifiViewModel = viewModel(
                    factory = viewModelFactory { initializer { WifiViewModel(route.siteId, container.session, container.operationsFor) } },
                )
                WifiRoute(vm, onBack = { navController.popBackStack() })
            }
            composable<StatusDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<StatusDestination>()
                val vm: StatusViewModel = viewModel(
                    factory = viewModelFactory { initializer { StatusViewModel(route.nodeId, container.session, container.adminFor) } },
                )
                StatusRoute(vm, onBack = { navController.popBackStack() })
            }
            composable<LeasesDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<LeasesDestination>()
                val prefill = route.mac?.let { LeasePrefill(it, route.ip, route.name) }
                val vm: LeasesViewModel = viewModel(
                    factory = viewModelFactory { initializer { LeasesViewModel(route.nodeId, container.session, container.adminFor, prefill) } },
                )
                LeasesRoute(vm, onBack = { navController.popBackStack() })
            }
            composable<ForwardsDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<ForwardsDestination>()
                val vm: ForwardsViewModel = viewModel(
                    factory = viewModelFactory { initializer { ForwardsViewModel(route.nodeId, container.session, container.adminFor) } },
                )
                ForwardsRoute(vm, onBack = { navController.popBackStack() })
            }
            composable<WirelessDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<WirelessDestination>()
                val vm: WirelessViewModel = viewModel(
                    factory = viewModelFactory { initializer { WirelessViewModel(route.nodeId, container.session, container.adminFor) } },
                )
                WirelessRoute(vm, onBack = { navController.popBackStack() })
            }
            composable<WanDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<WanDestination>()
                val vm: WanViewModel = viewModel(
                    factory = viewModelFactory { initializer { WanViewModel(route.nodeId, container.session, container.adminFor) } },
                )
                WanRoute(vm, onBack = { navController.popBackStack() })
            }
            composable<SystemDestination> { backStackEntry ->
                val route = backStackEntry.toRoute<SystemDestination>()
                val vm: SystemViewModel = viewModel(
                    factory = viewModelFactory { initializer { SystemViewModel(route.nodeId, container.session, container.adminFor) } },
                )
                SystemRoute(vm, onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
