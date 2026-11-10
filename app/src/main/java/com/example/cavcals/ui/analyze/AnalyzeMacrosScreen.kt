package com.example.cavcals.ui.analyze

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
 * Placeholder for the signed-in landing screen. The real Analyze screen will replace this
 * in a later task; for now it proves the login reached the signed-in branch.
 */
@Composable
fun AnalyzeMacrosScreen(
    signedInEmail: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.analyze_macros_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        if (signedInEmail != null) {
            Text(
                text = stringResource(R.string.analyze_macros_signed_in_as, signedInEmail),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
