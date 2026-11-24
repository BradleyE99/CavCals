// analyzeMeal: photo in, per-item macros + plate totals out.
//
//   Android app --(base64 photo)--> analyzeMeal
//       1. Claude vision  -> [{ name, usda_query, grams_est }]
//       2. USDA FDC       -> per-100 g macros (cached in foods/{foodId})
//       3. scale + sum    -> items[] and totals
//
// The photo is only held in memory for this request and never stored,
// matching the "Photo privacy" promise in the slides.
import Anthropic from "@anthropic-ai/sdk";
import { initializeApp } from "firebase-admin/app";
import { defineSecret } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { detectFoods, type MediaType } from "./vision.js";
import { lookupFood, type Macros } from "./usda.js";
import { scale, sum } from "./macros.js";
import { DAILY_SCAN_LIMIT, tryConsumeScan } from "./quota.js";

initializeApp();

const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");
const USDA_API_KEY = defineSecret("USDA_API_KEY");

const MAX_IMAGE_BYTES = 5 * 1024 * 1024; // Claude's per-image limit
const ALLOWED_TYPES: MediaType[] = ["image/jpeg", "image/png", "image/webp"];

interface AnalyzeRequest {
  imageBase64: string;
  mediaType?: MediaType;
}

export interface AnalyzedItem {
  name: string; // what Claude saw, e.g. "Grilled chicken breast"
  foodId: string | null; // foods/{foodId}, null if USDA had no match
  usdaName: string | null;
  fdcId: number | null;
  grams: number; // Claude's estimate; the user can edit this in the app
  confidence: "low" | "medium" | "high";
  per100g: Macros | null; // send this back down so the client can rescale
  macros: Macros | null; // per100g * grams / 100
}

export const analyzeMeal = onCall(
  {
    secrets: [ANTHROPIC_API_KEY, USDA_API_KEY],
    timeoutSeconds: 120,
    memory: "512MiB",
    // enforceAppCheck: true,  // turn on once App Check is set up, to block non-app callers
  },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");

    const { imageBase64, mediaType = "image/jpeg" } = (request.data ?? {}) as AnalyzeRequest;
    if (typeof imageBase64 !== "string" || imageBase64.length === 0) {
      throw new HttpsError("invalid-argument", "imageBase64 is required.");
    }
    if (!ALLOWED_TYPES.includes(mediaType)) {
      throw new HttpsError("invalid-argument", `mediaType must be one of ${ALLOWED_TYPES.join(", ")}.`);
    }
    if (Math.floor((imageBase64.length * 3) / 4) > MAX_IMAGE_BYTES) {
      throw new HttpsError("invalid-argument", "Image is over 5 MB. Downscale it on the device first.");
    }

    // Checked after input validation (a malformed request shouldn't use up a scan)
    // and before Claude runs (that's the part that costs money).
    if (!(await tryConsumeScan(request.auth.uid))) {
      throw new HttpsError("resource-exhausted", `Daily limit of ${DAILY_SCAN_LIMIT} scans reached. Try again tomorrow.`);
    }

    const client = new Anthropic({ apiKey: ANTHROPIC_API_KEY.value() });

    let detected;
    try {
      detected = await detectFoods(client, imageBase64, mediaType);
    } catch (err) {
      logger.error("vision step failed", err);
      throw new HttpsError("internal", "Couldn't analyze the photo. Try again.");
    }
    if (detected.length === 0) {
      return { items: [], totals: sum([]), message: "No food detected in this photo." };
    }

    const items: AnalyzedItem[] = await Promise.all(
      detected.map(async (d) => {
        const grams = Math.round(d.grams_est);
        try {
          const food = await lookupFood(d.usda_query, USDA_API_KEY.value());
          return {
            name: d.name,
            foodId: food?.foodId ?? null,
            usdaName: food?.name ?? null,
            fdcId: food?.fdcId ?? null,
            grams,
            confidence: d.confidence,
            per100g: food?.per100g ?? null,
            macros: food ? scale(food.per100g, grams) : null,
          };
        } catch (err) {
          logger.warn(`USDA lookup failed for "${d.usda_query}"`, err);
          return { name: d.name, foodId: null, usdaName: null, fdcId: null, grams, confidence: d.confidence, per100g: null, macros: null };
        }
      }),
    );

    const totals = sum(items.flatMap((i) => (i.macros ? [i.macros] : [])));
    return { items, totals };
  },
);
