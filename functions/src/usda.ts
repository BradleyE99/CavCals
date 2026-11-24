// Step 2 of the route: look up per-100 g macros in USDA FoodData Central and
// cache them in Firestore foods/{foodId} so repeat lookups are free and fast.
import { getFirestore, FieldValue } from "firebase-admin/firestore";

export interface Macros {
  calories: number; // kcal
  protein: number; // g
  fat: number; // g
  carbs: number; // g
}

export interface FoodRecord {
  name: string; // USDA description, e.g. "Chicken, broilers or fryers, breast, meat only, cooked, roasted"
  fdcId: number;
  dataType: string;
  per100g: Macros;
  source: "usda-fdc";
}

const SEARCH_URL = "https://api.nal.usda.gov/fdc/v1/foods/search";

// USDA nutrient ids
const ENERGY_KCAL = 1008;
const ENERGY_ATWATER_GENERAL = 2047; // Foundation foods often report energy here instead
const ENERGY_ATWATER_SPECIFIC = 2048;
const PROTEIN = 1003;
const FAT = 1004;
const CARBS = 1005; // carbohydrate, by difference

interface SearchNutrient {
  nutrientId: number;
  unitName?: string;
  value?: number;
}
export interface SearchFood {
  fdcId: number;
  description: string;
  dataType: string;
  foodNutrients?: SearchNutrient[];
}

/** foods/{foodId}: a stable id derived from the search phrase. */
export function foodIdFor(query: string): string {
  return query
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_|_$/g, "")
    .slice(0, 120);
}

export function extractMacros(food: SearchFood): Macros | null {
  const byId = new Map<number, number>();
  for (const n of food.foodNutrients ?? []) {
    if (typeof n.value === "number" && (n.unitName ?? "").toUpperCase() !== "KJ") {
      byId.set(n.nutrientId, n.value);
    }
  }
  const protein = byId.get(PROTEIN);
  const fat = byId.get(FAT);
  const carbs = byId.get(CARBS);
  if (protein === undefined || fat === undefined || carbs === undefined) return null;
  const calories =
    byId.get(ENERGY_KCAL) ??
    byId.get(ENERGY_ATWATER_SPECIFIC) ??
    byId.get(ENERGY_ATWATER_GENERAL) ??
    4 * protein + 4 * carbs + 9 * fat; // Atwater estimate as a last resort
  return { calories, protein, fat, carbs };
}

async function searchUsda(query: string, apiKey: string): Promise<FoodRecord | null> {
  const res = await fetch(`${SEARCH_URL}?api_key=${encodeURIComponent(apiKey)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      query,
      // Generic, lab-analyzed foods. Values in these data types are per 100 g.
      // (Branded foods are per serving and noisy, so we skip them.)
      dataType: ["Foundation", "SR Legacy"],
      pageSize: 10,
    }),
  });
  if (!res.ok) throw new Error(`USDA search failed: ${res.status}`);
  const data = (await res.json()) as { foods?: SearchFood[] };

  // Take the best-ranked hit that has all four macros.
  for (const food of data.foods ?? []) {
    const per100g = extractMacros(food);
    if (per100g) {
      return { name: food.description, fdcId: food.fdcId, dataType: food.dataType, per100g, source: "usda-fdc" };
    }
  }
  return null;
}

/** Cached lookup: Firestore first, USDA on a miss. */
export async function lookupFood(query: string, apiKey: string): Promise<(FoodRecord & { foodId: string }) | null> {
  const foodId = foodIdFor(query);
  const ref = getFirestore().collection("foods").doc(foodId);

  const cached = await ref.get();
  if (cached.exists) return { foodId, ...(cached.data() as FoodRecord) };

  const record = await searchUsda(query, apiKey);
  if (!record) return null;
  await ref.set({ ...record, query, createdAt: FieldValue.serverTimestamp() });
  return { foodId, ...record };
}
