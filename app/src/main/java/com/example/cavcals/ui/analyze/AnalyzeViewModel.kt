package com.example.cavcals.ui.analyze

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cavcals.R
import com.example.cavcals.analyze.MealAnalysis
import com.example.cavcals.analyze.MealAnalyzer
import com.example.cavcals.auth.AuthRepository
import com.example.cavcals.data.MealRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Everything the Analyze screen draws.
 *
 * [gramsInputs] holds the raw text of each portion field, separate from the numbers in
 * [analysis]. That way the user can clear a field to type a new number without the app
 * forcing it back to "0" mid-edit.
 */
data class AnalyzeUiState(
    val photo: Bitmap? = null,
    val isAnalyzing: Boolean = false,
    val analysis: MealAnalysis? = null,
    val gramsInputs: List<String> = emptyList(),
    @param:StringRes val errorMessage: Int? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
)

/**
 * Photo -> Cloud Function -> editable result -> Firestore.
 *
 * Same unidirectional pattern as LoginViewModel: the screen sends events (photo picked, grams
 * edited, Save tapped), this class updates [uiState], and Compose redraws.
 * It is an AndroidViewModel only because decoding a photo Uri needs a ContentResolver.
 */
class AnalyzeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AnalyzeUiState())
    val uiState: StateFlow<AnalyzeUiState> = _uiState.asStateFlow()

    /** Called with the photo from the camera or the gallery. */
    fun onPhotoSelected(uri: Uri) {
        if (_uiState.value.isAnalyzing) return
        _uiState.value = AnalyzeUiState(isAnalyzing = true)

        viewModelScope.launch {
            try {
                // Decoding a 12 MP photo is slow, so it runs off the main thread.
                val bitmap = withContext(Dispatchers.IO) { decodeUpright(uri) }
                    ?: throw IllegalStateException("Could not read photo")
                _uiState.update { it.copy(photo = bitmap) }

                val analysis = MealAnalyzer.analyze(bitmap)
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        analysis = analysis,
                        gramsInputs = analysis.items.map { item -> item.grams.toString() },
                        errorMessage = if (analysis.items.isEmpty()) R.string.analyze_error_no_food else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isAnalyzing = false, errorMessage = messageFor(e)) }
            }
        }
    }

    /**
     * The user typed in a portion field. Only digits are kept. A valid number rescales that
     * item right away on the phone; the server is not called again.
     */
    fun onGramsChange(index: Int, text: String) {
        val digits = text.filter(Char::isDigit).take(5)
        _uiState.update { state ->
            val analysis = state.analysis ?: return@update state
            val inputs = state.gramsInputs.toMutableList().also { it[index] = digits }
            val grams = digits.toIntOrNull() ?: 0
            state.copy(
                analysis = analysis.withGrams(index, grams),
                gramsInputs = inputs,
                isSaved = false,
            )
        }
    }

    fun onSaveClick() {
        val state = _uiState.value
        val analysis = state.analysis ?: return
        val uid = AuthRepository.currentUser.value?.uid ?: return
        if (state.isSaving || state.isSaved || analysis.items.isEmpty()) return

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                MealRepository.saveMeal(uid, labelForNow(), analysis)
                _uiState.update { it.copy(isSaving = false, isSaved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = R.string.analyze_error_save) }
            }
        }
    }

    /** Clears the result so the user can scan another meal. */
    fun onStartOver() {
        if (_uiState.value.isAnalyzing) return
        _uiState.value = AnalyzeUiState()
    }

    // A simple default label from the time of day. The user does not have to pick one.
    private fun labelForNow(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 4..10 -> "Breakfast"
        in 11..15 -> "Lunch"
        in 16..21 -> "Dinner"
        else -> "Snack"
    }

    @StringRes
    private fun messageFor(error: Exception): Int = when {
        error is FirebaseNetworkException -> R.string.analyze_error_network
        error is FirebaseFunctionsException && error.code == FirebaseFunctionsException.Code.NOT_FOUND ->
            R.string.analyze_error_not_deployed
        error is FirebaseFunctionsException && error.code == FirebaseFunctionsException.Code.UNAVAILABLE ->
            R.string.analyze_error_network
        error is FirebaseFunctionsException && error.code == FirebaseFunctionsException.Code.UNAUTHENTICATED ->
            R.string.analyze_error_signed_out
        error is FirebaseFunctionsException && error.code == FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
            R.string.analyze_error_bad_photo
        error is IllegalStateException -> R.string.analyze_error_bad_photo
        else -> R.string.analyze_error_unknown
    }

    /**
     * Reads the photo at a reasonable size and turns it upright.
     *
     * inSampleSize asks the decoder to skip pixels (2 = half width and height), so a large camera
     * photo never sits fully in memory. Phone cameras often save the picture sideways and record
     * the real orientation in EXIF, so the bitmap is rotated to match before it is shown or sent.
     */
    private fun decodeUpright(uri: Uri, targetMaxSide: Int = 1568): Bitmap? {
        val resolver = getApplication<Application>().contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetMaxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val degrees = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
