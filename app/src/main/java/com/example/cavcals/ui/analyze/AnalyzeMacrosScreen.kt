package com.example.cavcals.ui.analyze

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cavcals.R
import com.example.cavcals.analyze.Macros
import com.example.cavcals.analyze.MealItem
import java.io.File
import kotlin.math.roundToInt

/**
 * Home tab: take or choose a meal photo, see what was found, fix portions, and save.
 *
 * The screen only renders [AnalyzeUiState] and forwards taps to [AnalyzeViewModel].
 */
@Composable
fun AnalyzeMacrosScreen(
    modifier: Modifier = Modifier,
    viewModel: AnalyzeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The camera app writes the full-size photo into a file we own, shared through FileProvider.
    // rememberSaveable keeps the Uri if Android recreates the activity while the camera is open.
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) viewModel.onPhotoSelected(uri)
    }
    // The system photo picker needs no storage permission. It's also the easiest way to demo
    // on the emulator: drag a food photo onto the emulator window, then choose it here.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhotoSelected(uri)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.analyze_macros_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        item { PhotoPreview(state) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val uri = newCameraUri(context)
                        pendingCameraUri = uri
                        takePicture.launch(uri)
                    },
                    enabled = !state.isAnalyzing,
                ) { Text(stringResource(R.string.analyze_take_photo)) }
                OutlinedButton(
                    onClick = {
                        pickPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !state.isAnalyzing,
                ) { Text(stringResource(R.string.analyze_choose_photo)) }
            }
        }

        if (state.isAnalyzing) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.analyze_in_progress))
                }
            }
        }

        state.errorMessage?.let { message ->
            item {
                Text(
                    text = stringResource(message),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        val analysis = state.analysis
        if (analysis != null && analysis.items.isNotEmpty()) {
            item {
                // Lazy items don't stack their children, so the two lines need their own Column.
                Column {
                    Text(
                        text = stringResource(R.string.analyze_found_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.analyze_found_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            itemsIndexed(analysis.items) { index, item ->
                FoodItemCard(
                    item = item,
                    gramsInput = state.gramsInputs.getOrElse(index) { item.grams.toString() },
                    onGramsChange = { viewModel.onGramsChange(index, it) },
                )
            }
            item { TotalsCard(analysis.totals) }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::onSaveClick,
                        enabled = !state.isSaving && !state.isSaved,
                    ) {
                        Text(
                            stringResource(
                                when {
                                    state.isSaved -> R.string.analyze_saved
                                    state.isSaving -> R.string.analyze_saving
                                    else -> R.string.analyze_save
                                },
                            ),
                        )
                    }
                    TextButton(onClick = viewModel::onStartOver) {
                        Text(stringResource(R.string.analyze_start_over))
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoPreview(state: AnalyzeUiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val photo = state.photo
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = stringResource(R.string.analyze_photo_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = stringResource(R.string.analyze_photo_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun FoodItemCard(
    item: MealItem,
    gramsInput: String,
    onGramsChange: (String) -> Unit,
) {
    // Low-confidence guesses get a tinted card so the user knows to double-check them.
    val lowConfidence = item.confidence == "low"
    Card(
        colors = if (lowConfidence) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    item.usdaName?.let {
                        Text(
                            text = stringResource(R.string.analyze_matched_as, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (lowConfidence) {
                        Text(
                            text = stringResource(R.string.analyze_low_confidence),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = gramsInput,
                    onValueChange = onGramsChange,
                    label = { Text(stringResource(R.string.analyze_grams_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            val macros = item.macros
            Text(
                text = if (macros != null) macroLine(macros) else stringResource(R.string.analyze_no_match),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TotalsCard(totals: Macros) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.analyze_totals_title), style = MaterialTheme.typography.titleSmall)
            Text(macroLine(totals), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "248 kcal · P 47 g · C 0 g · F 5 g". Shared with the Calendar tab. */
fun macroLine(m: Macros): String =
    "${m.calories.roundToInt()} kcal · P ${m.protein.roundToInt()} g · " +
        "C ${m.carbs.roundToInt()} g · F ${m.fat.roundToInt()} g"

// A fresh file per photo, so a retake never shows the previous picture from a cache.
private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "meal_photos").apply { mkdirs() }
    val file = File(dir, "meal_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
