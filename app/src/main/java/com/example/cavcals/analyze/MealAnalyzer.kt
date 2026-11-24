package com.example.cavcals.analyze

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.cavcals.BuildConfig
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Calories (kcal) and grams of protein, fat, and carbs. */
data class Macros(val calories: Double, val protein: Double, val fat: Double, val carbs: Double) {
    operator fun plus(o: Macros) =
        Macros(calories + o.calories, protein + o.protein, fat + o.fat, carbs + o.carbs)

    companion object {
        val ZERO = Macros(0.0, 0.0, 0.0, 0.0)
    }
}

/** One food the backend found on the plate. */
data class MealItem(
    val name: String,         // what Claude saw, e.g. "Grilled chicken breast"
    val foodId: String?,      // foods/{foodId} in Firestore; null when USDA had no match
    val usdaName: String?,    // the USDA entry that was matched, shown so the user can sanity-check it
    val grams: Int,           // Claude's estimate at first; the user can edit it
    val confidence: String,   // "low" | "medium" | "high"; "low" is highlighted so the user checks it
    val per100g: Macros?,     // null = USDA had no match, so there is nothing to scale
) {
    /**
     * Macros for the grams on the plate: per100g x grams / 100.
     * Because the server sends per100g, the app can rescale on every edit without a network call.
     */
    val macros: Macros?
        get() = per100g?.let {
            val f = grams / 100.0
            Macros(it.calories * f, it.protein * f, it.fat * f, it.carbs * f)
        }
}

data class MealAnalysis(val items: List<MealItem>) {
    /** Plate total = sum of every item that has a USDA match. */
    val totals: Macros
        get() = items.mapNotNull { it.macros }.fold(Macros.ZERO, Macros::plus)

    /** Returns a copy with one item's grams changed. Totals update because they are computed. */
    fun withGrams(index: Int, grams: Int) =
        copy(items = items.toMutableList().also { it[index] = it[index].copy(grams = grams) })
}

/**
 * Client for the analyzeMeal Firebase Cloud Function (see /functions in the repo root).
 *
 * The phone never talks to Claude or USDA directly. API keys inside an APK can be extracted, so
 * both keys live on the server as Firebase secrets, and the function only accepts signed-in users.
 */
object MealAnalyzer {
    private val callable = FirebaseFunctions.getInstance()
        .apply {
            // Debug builds can point at the Firebase emulator on the laptop (see the
            // cavcals.useFunctionsEmulator note in app/build.gradle.kts). This must run before
            // the first call, which is why it sits where the instance is first created.
            if (BuildConfig.USE_FUNCTIONS_EMULATOR) {
                Log.i("MealAnalyzer", "Using Functions emulator at ${BuildConfig.EMULATOR_HOST}:5001")
                useEmulator(BuildConfig.EMULATOR_HOST, 5001)
            }
        }
        .getHttpsCallable("analyzeMeal")
        .apply { setTimeout(120, TimeUnit.SECONDS) }

    /** Requires a signed-in Firebase user (the function rejects anonymous calls). */
    suspend fun analyze(photo: Bitmap): MealAnalysis {
        val base64 = withContext(Dispatchers.Default) { encodeForUpload(photo) }
        val result = callable.call(mapOf("imageBase64" to base64, "mediaType" to "image/jpeg")).await()

        @Suppress("UNCHECKED_CAST")
        val data = result.data as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val rawItems = data["items"] as? List<Map<String, Any?>> ?: emptyList()
        return MealAnalysis(rawItems.map { it.toMealItem() })
    }

    // Claude downsizes anything over ~1568 px on the long side anyway, so shrinking first
    // saves upload time and keeps the payload well under the 5 MB limit.
    private fun encodeForUpload(src: Bitmap, maxSide: Int = 1568): String {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true,
            )
        } else {
            src
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun Map<String, Any?>.toMealItem(): MealItem {
        @Suppress("UNCHECKED_CAST")
        val p = this["per100g"] as? Map<String, Any?>
        return MealItem(
            name = this["name"] as? String ?: "Unknown food",
            foodId = this["foodId"] as? String,
            usdaName = this["usdaName"] as? String,
            grams = (this["grams"] as? Number)?.toInt() ?: 0,
            confidence = this["confidence"] as? String ?: "medium",
            per100g = p?.let {
                Macros(
                    (it["calories"] as? Number)?.toDouble() ?: 0.0,
                    (it["protein"] as? Number)?.toDouble() ?: 0.0,
                    (it["fat"] as? Number)?.toDouble() ?: 0.0,
                    (it["carbs"] as? Number)?.toDouble() ?: 0.0,
                )
            },
        )
    }
}
