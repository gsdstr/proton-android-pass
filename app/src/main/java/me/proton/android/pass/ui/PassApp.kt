package me.proton.android.pass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.DrawerState
import androidx.compose.material.DrawerValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.insets.ProvideWindowInsets
import com.google.accompanist.navigation.animation.AnimatedNavHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.proton.android.pass.ui.navigation.NavItem
import me.proton.android.pass.ui.navigation.appGraph
import me.proton.android.pass.ui.navigation.rememberAnimatedNavController
import me.proton.android.pass.ui.navigation.rememberAppNavigator
import me.proton.android.pass.ui.shared.ConfirmSignOutDialog
import me.proton.core.compose.theme.ProtonTheme
import me.proton.core.pass.presentation.components.navigation.AuthNavigation
import me.proton.core.pass.presentation.components.navigation.drawer.ModalNavigationDrawer
import me.proton.core.pass.presentation.components.navigation.drawer.NavDrawerNavigation
import me.proton.core.pass.presentation.components.navigation.drawer.NavigationDrawerSection

@OptIn(
    ExperimentalAnimationApi::class,
    ExperimentalMaterialApi::class,
    ExperimentalComposeUiApi::class
)
@Composable
fun PassApp(
    modifier: Modifier = Modifier,
    authNavigation: AuthNavigation,
    startDestination: String = NavItem.Home.route,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    drawerState: DrawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
    appViewModel: AppViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) {
        appViewModel.onStart()
    }

    ProtonTheme {
        ProvideWindowInsets {
            val drawerUiState by appViewModel.drawerUiState.collectAsStateWithLifecycle()
            val navController = rememberAnimatedNavController()
            val appNavigator = rememberAppNavigator(navController)
            val navDrawerNavigation = NavDrawerNavigation(
                onNavHome = {
                    appViewModel.onDrawerSectionChanged(NavigationDrawerSection.Items)
                    appNavigator.navigate(NavItem.Home)
                },
                onNavSettings = {
                    appViewModel.onDrawerSectionChanged(NavigationDrawerSection.Settings)
                    appNavigator.navigate(NavItem.Settings)
                },
                onNavTrash = {
                    appViewModel.onDrawerSectionChanged(NavigationDrawerSection.Trash)
                    appNavigator.navigate(NavItem.Trash)
                },
                onNavHelp = {
                    appViewModel.onDrawerSectionChanged(NavigationDrawerSection.Help)
                    appNavigator.navigate(NavItem.Help)
                }
            )
            BackHandler(drawerState.isOpen) { coroutineScope.launch { drawerState.close() } }
            AnimatedNavHost(
                modifier = modifier,
                navController = navController,
                startDestination = startDestination
            ) {
                appGraph(
                    appNavigation = appNavigator,
                    navigationDrawer = { content ->
                        var showSignOutDialog by remember { mutableStateOf(false) }

                        ModalNavigationDrawer(
                            drawerUiState = drawerUiState,
                            drawerState = drawerState,
                            navDrawerNavigation = navDrawerNavigation,
                            authNavigation = authNavigation,
                            onSignOutClick = { showSignOutDialog = true },
                            signOutDialog = {
                                if (showSignOutDialog) {
                                    ConfirmSignOutDialog(
                                        state = showSignOutDialog,
                                        onDismiss = { showSignOutDialog = false },
                                        onConfirm = { authNavigation.onRemove(null) }
                                    )
                                }
                            },
                            content = content
                        )
                    },
                    onDrawerIconClick = { coroutineScope.launch { drawerState.open() } }
                )
            }
        }
    }
}
