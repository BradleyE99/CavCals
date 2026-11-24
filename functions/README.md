# CavCals meal analyzer: photo → macros

## The route in one picture

```
Android app ──(JPEG, base64)──▶ analyzeMeal (Firebase Cloud Function)
                                   │
                                   ├─1─▶ Claude vision: "what foods, roughly how many grams?"
                                   │       → [{ name, usda_query, grams_est, confidence }]
                                   │
                                   ├─2─▶ USDA FoodData Central: per-100 g macros
                                   │       (cached in Firestore foods/{foodId})
                                   │
                                   └─3─▶ scale by grams, sum
◀──────────── { items[], totals } ─┘
User edits grams → app rescales locally (no second call)
```

## Why this route (and not a big image dataset)

**Analogy:** think of a dietitian looking at your plate.
- Their **eyes** recognize "that's roasted chicken and white rice." → **Claude vision**
- They don't *see* calories. They know them from a **reference book**. → **USDA database**
- They **estimate the portion**, and they'd ask you if unsure. → **Claude's gram estimate + the user's edit**

Only the eyes need to be "smart." The reference book is just a lookup.

**Why not train on Food-101 or similar?**
- Image datasets label *what* a food is ("chicken_wings"), not *how much macro* it has. You'd still need a nutrition DB.
- Classifiers trained on them predict **one label per photo**. Real plates have 3–5 foods.
- Training, evaluating, and shipping a model is weeks of work. M2 is today.

**Why a Cloud Function instead of calling Claude from the app?**
- An API key inside an APK can be extracted in minutes. The function keeps both keys server-side as Firebase secrets.
- It also enforces "signed-in users only," and the photo never touches storage (matches your Photo-privacy slide).

**Why USDA FoodData Central?**
- Free, government-maintained, about 400k foods. The `Foundation` and `SR Legacy` sets are lab-analyzed and reported **per 100 g**, which makes scaling trivial.
- Caching results in `foods/{foodId}` fills your read-only catalog as people use the app.

## The math

For each item $i$ with weight $w_i$ grams and per-100 g value $m_{i}^{(100)}$:

$$
m_i = m_i^{(100)} \cdot \frac{w_i}{100}
\qquad
M_{\text{plate}} = \sum_i m_i
$$

applied separately to calories, protein, fat, and carbs. If USDA omits energy, the function falls back to the Atwater estimate:

$$
\text{kcal} \approx 4P + 4C + 9F
$$

## Honest limitations (put these in your demo answers)

- **Portion size is the weak link.** A photo has no scale reference, so gram estimates can be off by ±30% or more. That's why the app returns `per100g` for every item and lets the user correct grams. Keep that edit box. Low-confidence items should be visually flagged.
- **Hidden ingredients** (cooking oil, butter, sugar in sauces) are invisible. Expect calories to read low on restaurant food.
- **USDA match quality** depends on the search phrase. The first hit for a vague phrase can be odd. You can see what matched in `usdaName`. A "change food" option in the UI fixes the rare miss.
- **Your slides say "ML food library."** That's no longer accurate. Say "AI vision model (Claude) + USDA nutrition database" instead.

## Files

| File | What it does |
|---|---|
| `functions/src/index.ts` | `analyzeMeal` callable: auth check, input validation, orchestration, scaling |
| `functions/src/vision.ts` | Claude call with a strict JSON schema (structured outputs) |
| `functions/src/usda.ts` | USDA search, macro extraction, Firestore cache |
| `functions/src/macros.ts` | Scaling and plate totals (pure, unit-tested) |
| `functions/src/quota.ts` | Per-user daily scan limit |
| `functions/src/test/` | Unit tests (`npm test`) |
| `app/.../analyze/MealAnalyzer.kt` | Downscale + upload photo, parse result, local rescale (already wired into the app) |

## Setup (about 15 minutes)

1. **Firebase Blaze plan.** Cloud Functions require the pay-as-you-go plan (it has a generous free tier, and you can set a budget alert). Console → ⚙ → Usage and billing.
2. **Get keys**
   - Anthropic: https://platform.claude.com → API keys. Add a small credit and set a spend limit.
   - USDA: https://fdc.nal.usda.gov/api-key-signup (free, instant email).
3. **Install the tools** (from the repo root; `firebase.json` and `.firebaserc` are already set up):
   ```bash
   npm install -g firebase-tools
   firebase login
   cd functions && npm install && cd ..
   ```
4. **Store the keys as secrets** (never in code or git):
   ```bash
   firebase functions:secrets:set ANTHROPIC_API_KEY
   firebase functions:secrets:set USDA_API_KEY
   ```
5. **Deploy** (from the repo root; the build runs automatically first)
   ```bash
   firebase deploy --only functions
   ```
6. **Android:** already done. `AnalyzeViewModel` calls `MealAnalyzer.analyze(bitmap)` after the user picks a photo.
7. **Saving a meal:** already done. `MealRepository.saveMeal` writes the confirmed items and totals to `users/{uid}/meals/{mealId}`. The photo is never saved.

## Test locally (no deploy, no Blaze plan needed)

```
Android emulator (app) ──▶ 10.0.2.2:5001 ──▶ Functions emulator on your laptop
                                               ├─▶ real Claude API (your key, ~1–2¢ per scan)
                                               ├─▶ real USDA API (free)
                                               └─▶ Firestore emulator :8080 (food cache + scan counts)
```

1. **Keys:** copy `functions/.secret.local.example` to `functions/.secret.local` and fill in both keys. That file is git-ignored.
2. **Start the emulators:** `cd functions && npm run serve`. Wait for "All emulators ready". The Emulator UI at http://127.0.0.1:4000 shows function logs and the local Firestore. `firebase login` isn't needed for this.
3. **Point the app at it:** add `cavcals.useFunctionsEmulator=true` to `local.properties` (repo root), then rebuild and run the **debug** build. Logcat shows `Using Functions emulator at 10.0.2.2:5001`. Remove the line, or set it to `false`, to go back to the deployed function. Release builds always use the deployed function.
4. **Sign in normally** with a real account. The Functions emulator accepts real sign-ins, so no Auth emulator is needed. Saved meals still go to the real Firestore; only the function's own data is local.
5. **Add a photo:** drag a food `.jpg` onto the emulator window (it lands in Downloads and shows in the photo picker). You can also run `adb push plate.jpg /sdcard/Pictures/`, or set the virtual device's back camera to **Webcam0** in Device Manager → Edit → Advanced to photograph a real plate.

**Physical phone instead:** set `cavcals.emulatorHost=<your laptop's LAN IP>` in `local.properties`, add that IP to `app/src/debug/res/xml/network_security_config.xml`, and start the emulators with `--host 0.0.0.0`. You may also need to allow port 5001 through the Windows firewall.

## Unit tests

`cd functions && npm test` runs the macro math, USDA parsing, and quota date tests. No network or keys needed.

## Daily scan limit

Each user gets `DAILY_SCAN_LIMIT` (30) scans per UTC day, counted in `usage/{uid}/days/{yyyy-mm-dd}` before Claude runs. Past the limit the function returns `resource-exhausted` and the app shows "You've reached today's scan limit." Change the number in `functions/src/quota.ts`. In the emulator, delete the day's document in the Emulator UI to reset it.

## Cost

- One scan ≈ 2–3k input tokens (mostly the image) + a few hundred output tokens.
- On Claude Opus 5.5 ($4 / $20 per million tokens) that's **about 1–2¢ per scan**. A class demo costs pennies.
- If you want it cheaper at scale, `MODEL` in `vision.ts` is a one-line change (e.g. `claude-haiku-5-5`, roughly 40× cheaper). Test accuracy on your own plates before switching.
- USDA is free (1,000 requests/hour per key), and the Firestore cache means repeat foods don't hit it.

## Response shape

```json
{
  "items": [
    {
      "name": "Roasted chicken breast",
      "foodId": "chicken_breast_roasted",
      "usdaName": "Chicken, broilers or fryers, breast, meat only, cooked, roasted",
      "fdcId": 171477,
      "grams": 150,
      "confidence": "medium",
      "per100g": { "calories": 165, "protein": 31, "fat": 3.6, "carbs": 0 },
      "macros":  { "calories": 247.5, "protein": 46.5, "fat": 5.4, "carbs": 0 }
    }
  ],
  "totals": { "calories": 247.5, "protein": 46.5, "fat": 5.4, "carbs": 0 }
}
```
