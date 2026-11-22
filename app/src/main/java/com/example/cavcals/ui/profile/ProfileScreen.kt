package com.example.cavcals.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cavcals.R
import com.example.cavcals.data.SavedMeal
import com.example.cavcals.ui.analyze.macroLine
import java.text.DateFormat
import java.util.Calendar
import java.util.TimeZone

/**
 * Calendar tab (the "Profile" screen in the design): pick a day, see that day's meals and totals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineMedium)
                state.email?.let {
                    Text(stringResource(R.string.analyze_macros_signed_in_as, it), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::onPreviousDay) { Text("‹") }
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                    Text(DateFormat.getDateInstance(DateFormat.FULL).format(state.day))
                }
                TextButton(onClick = viewModel::onNextDay) { Text("›") }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.profile_day_totals), style = MaterialTheme.typography.titleSmall)
                    Text(macroLine(state.dayTotals), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        when {
            state.isLoading -> item { Text(stringResource(R.string.profile_loading)) }
            state.loadFailed -> item {
                Text(stringResource(R.string.profile_load_failed), color = MaterialTheme.colorScheme.error)
            }
            state.meals.isEmpty() -> item { Text(stringResource(R.string.profile_no_meals)) }
            else -> items(state.meals, key = { it.id }) { meal -> MealCard(meal) }
        }

        item {
            TextButton(onClick = viewModel::onSignOut) { Text(stringResource(R.string.profile_sign_out)) }
        }
    }

    if (showDatePicker) {
        // The picker works in UTC midnight, so the selected local day is converted before seeding it.
        val local = Calendar.getInstance().apply { time = state.day }
        val initialUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(viewModel::onDatePicked)
                    showDatePicker = false
                }) { Text(stringResource(R.string.profile_pick_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.profile_pick_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun MealCard(meal: SavedMeal) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(meal.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(meal.eatenAt),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (meal.foodNames.isNotEmpty()) {
                Text(meal.foodNames.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
            Text(macroLine(meal.totals), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
