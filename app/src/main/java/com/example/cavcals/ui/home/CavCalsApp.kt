package com.example.cavcals.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.cavcals.R
import com.example.cavcals.ui.analyze.AnalyzeMacrosScreen
import com.example.cavcals.ui.compare.MacroCompareScreen
import com.example.cavcals.ui.profile.ProfileScreen

/** The three bottom-nav tabs, in the order they appear. */
enum class Tab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    Calendar(R.string.tab_calendar, R.drawable.ic_tab_calendar),
    Home(R.string.tab_home, R.drawable.ic_tab_home),
    Macro(R.string.tab_macro, R.drawable.ic_tab_macro),
}

/**
 * The signed-in app: a bottom navigation bar with Calendar, Home (Analyze Macros), and Macro.
 *
 * The selected tab is one piece of saveable state rather than a Navigation Compose graph.
 * Analogy: it's a TV remote with three channel buttons, not a browser with history. With only
 * three flat tabs and no deep links yet, that is all we need. If the app later gets detail
 * screens (a meal detail, a group page), switch this to Navigation Compose (Lecture 07).
 */
@Composable
fun CavCalsApp() {
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Home) }

    // Back from Calendar or Macro returns to Home first; Back on Home leaves the app.
    BackHandler(enabled = selectedTab != Tab.Home) { selectedTab = Tab.Home }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (selectedTab) {
            Tab.Calendar -> ProfileScreen(modifier = contentModifier)
            Tab.Home -> AnalyzeMacrosScreen(modifier = contentModifier)
            Tab.Macro -> MacroCompareScreen(modifier = contentModifier)
        }
    }
}
