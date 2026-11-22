package com.example.cavcals.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cavcals.analyze.Macros
import com.example.cavcals.auth.AuthRepository
import com.example.cavcals.data.MealRepository
import com.example.cavcals.data.SavedMeal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.Date

data class ProfileUiState(
    val email: String? = null,
    val day: Date = startOfToday(),
    val meals: List<SavedMeal> = emptyList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
) {
    val dayTotals: Macros get() = meals.fold(Macros.ZERO) { sum, meal -> sum + meal.totals }
}

/**
 * Calendar tab: one selected day and the meals saved on it.
 *
 * [selectedDay] is the only thing this ViewModel owns. flatMapLatest swaps the Firestore
 * listener whenever the day changes, and cancels the old one, so a slow answer for yesterday
 * can never overwrite today's list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel : ViewModel() {

    private val selectedDay = MutableStateFlow(startOfToday())

    private sealed interface MealsResult {
        data object Loading : MealsResult
        data class Loaded(val meals: List<SavedMeal>) : MealsResult
        data object Failed : MealsResult
    }

    private val mealsForDay = combine(AuthRepository.currentUser, selectedDay) { user, day -> user to day }
        .flatMapLatest { (user, day) ->
            if (user == null) {
                flowOf(MealsResult.Loaded(emptyList()))
            } else {
                MealRepository.mealsOn(user.uid, day)
                    .map<List<SavedMeal>, MealsResult> { MealsResult.Loaded(it) }
                    .onStart { emit(MealsResult.Loading) }
                    .catch { emit(MealsResult.Failed) }
            }
        }

    val uiState: StateFlow<ProfileUiState> =
        combine(AuthRepository.currentUser, selectedDay, mealsForDay) { user, day, result ->
            ProfileUiState(
                email = user?.email,
                day = day,
                meals = (result as? MealsResult.Loaded)?.meals.orEmpty(),
                isLoading = result is MealsResult.Loading,
                loadFailed = result is MealsResult.Failed,
            )
        }.stateIn(
            scope = viewModelScope,
            // Keep the listener alive 5 s after the screen stops, so rotating the phone or
            // switching tabs quickly doesn't restart the Firestore query.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProfileUiState(),
        )

    fun onPreviousDay() = shiftDay(-1)
    fun onNextDay() = shiftDay(1)

    /** The Material date picker reports UTC midnight; convert that calendar date to local midnight. */
    fun onDatePicked(utcMillis: Long) {
        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        selectedDay.value = Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        }.time
    }

    fun onSignOut() = AuthRepository.signOut()

    private fun shiftDay(days: Int) {
        selectedDay.value = Calendar.getInstance().apply {
            time = selectedDay.value
            add(Calendar.DAY_OF_MONTH, days)
        }.time
    }
}

fun startOfToday(): Date = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.time
