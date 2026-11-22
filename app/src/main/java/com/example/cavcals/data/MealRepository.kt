package com.example.cavcals.data

import com.example.cavcals.analyze.MealAnalysis
import com.example.cavcals.analyze.Macros
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A meal as the Calendar tab shows it. */
data class SavedMeal(
    val id: String,
    val label: String,
    val eatenAt: Date,
    val foodNames: List<String>,
    val totals: Macros,
)

/**
 * Reads and writes meals in Firestore at users/{uid}/meals/{mealId}.
 *
 * Each meal stores a `dayKey` like "2026-11-22" in the phone's local time zone. The Calendar tab
 * asks for one day with a simple equality query on that field, which needs no composite index.
 * Only the confirmed numbers are saved. The photo itself is never uploaded to storage.
 */
object MealRepository {

    private val firestore = FirebaseFirestore.getInstance()

    private fun mealsOf(uid: String) =
        firestore.collection("users").document(uid).collection("meals")

    /** "yyyy-MM-dd" in local time. java.time needs API 26 and this app supports API 24. */
    fun dayKey(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    suspend fun saveMeal(uid: String, label: String, analysis: MealAnalysis, eatenAt: Date = Date()) {
        val items = analysis.items.map { item ->
            mapOf(
                "name" to item.name,
                "foodId" to item.foodId,
                "usdaName" to item.usdaName,
                "grams" to item.grams,
                "confidence" to item.confidence,
                "per100g" to item.per100g?.toMap(),
                "macros" to item.macros?.toMap(),
            )
        }
        val meal = mapOf(
            "label" to label,
            "eatenAt" to Timestamp(eatenAt),
            "dayKey" to dayKey(eatenAt),
            "items" to items,
            "totals" to analysis.totals.toMap(),
        )
        mealsOf(uid).add(meal).await()
    }

    /**
     * Live list of meals for one day. callbackFlow turns Firestore's listener into a Flow:
     * every change (including a meal saved from the Analyze tab) emits a new list, and
     * awaitClose removes the listener when the screen stops collecting.
     */
    fun mealsOn(uid: String, day: Date): Flow<List<SavedMeal>> = callbackFlow {
        val registration = mealsOf(uid)
            .whereEqualTo("dayKey", dayKey(day))
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val meals = snapshot?.documents.orEmpty()
                    .map { it.toSavedMeal() }
                    .sortedBy { it.eatenAt }
                trySend(meals)
            }
        awaitClose { registration.remove() }
    }

    private fun Macros.toMap() = mapOf(
        "calories" to calories,
        "protein" to protein,
        "fat" to fat,
        "carbs" to carbs,
    )

    private fun DocumentSnapshot.toSavedMeal(): SavedMeal {
        @Suppress("UNCHECKED_CAST")
        val totals = get("totals") as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val items = get("items") as? List<Map<String, Any?>> ?: emptyList()
        return SavedMeal(
            id = id,
            label = getString("label") ?: "Meal",
            eatenAt = getTimestamp("eatenAt")?.toDate() ?: Date(0),
            foodNames = items.mapNotNull { it["name"] as? String },
            totals = Macros(
                (totals["calories"] as? Number)?.toDouble() ?: 0.0,
                (totals["protein"] as? Number)?.toDouble() ?: 0.0,
                (totals["fat"] as? Number)?.toDouble() ?: 0.0,
                (totals["carbs"] as? Number)?.toDouble() ?: 0.0,
            ),
        )
    }
}
