package com.example.cavcals.ui.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.cavcals.R

/**
 * Macro tab placeholder. The real screen compares your day against your targets and your
 * group's shared meals (groups/{groupId}, sharedMeals/{postId}). That's the next milestone.
 */
@Composable
fun MacroCompareScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.compare_coming_soon), style = MaterialTheme.typography.bodyMedium)
    }
}
