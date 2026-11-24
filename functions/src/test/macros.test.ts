// Unit tests for the pure parts of analyzeMeal. Run with `npm test`.
// They touch no network and no Firestore.
import { test } from "node:test";
import assert from "node:assert/strict";
import { scale, sum } from "../macros.js";
import { extractMacros, foodIdFor, type SearchFood } from "../usda.js";
import { utcDayKey } from "../quota.js";

test("scale multiplies per-100 g values by grams / 100 and rounds to 0.1", () => {
  const chicken = { calories: 165, protein: 31, fat: 3.6, carbs: 0 };
  assert.deepEqual(scale(chicken, 150), { calories: 247.5, protein: 46.5, fat: 5.4, carbs: 0 });
  assert.deepEqual(scale(chicken, 0), { calories: 0, protein: 0, fat: 0, carbs: 0 });
});

test("sum adds every field and returns zeros for an empty plate", () => {
  assert.deepEqual(sum([]), { calories: 0, protein: 0, fat: 0, carbs: 0 });
  assert.deepEqual(
    sum([
      { calories: 247.5, protein: 46.5, fat: 5.4, carbs: 0 },
      { calories: 195, protein: 4, fat: 0.4, carbs: 42.3 },
    ]),
    { calories: 442.5, protein: 50.5, fat: 5.8, carbs: 42.3 },
  );
});

const food = (nutrients: SearchFood["foodNutrients"]): SearchFood => ({
  fdcId: 1,
  description: "Test food",
  dataType: "SR Legacy",
  foodNutrients: nutrients,
});

test("extractMacros reads kcal energy (1008) and the three macros", () => {
  const m = extractMacros(
    food([
      { nutrientId: 1008, unitName: "KCAL", value: 165 },
      { nutrientId: 1003, unitName: "G", value: 31 },
      { nutrientId: 1004, unitName: "G", value: 3.6 },
      { nutrientId: 1005, unitName: "G", value: 0 },
    ]),
  );
  assert.deepEqual(m, { calories: 165, protein: 31, fat: 3.6, carbs: 0 });
});

test("extractMacros ignores energy reported in kJ", () => {
  const m = extractMacros(
    food([
      { nutrientId: 1008, unitName: "kJ", value: 690 },
      { nutrientId: 2047, unitName: "KCAL", value: 165 },
      { nutrientId: 1003, unitName: "G", value: 31 },
      { nutrientId: 1004, unitName: "G", value: 3.6 },
      { nutrientId: 1005, unitName: "G", value: 0 },
    ]),
  );
  assert.equal(m?.calories, 165);
});

test("extractMacros prefers Atwater specific (2048) over general (2047)", () => {
  const m = extractMacros(
    food([
      { nutrientId: 2047, unitName: "KCAL", value: 170 },
      { nutrientId: 2048, unitName: "KCAL", value: 160 },
      { nutrientId: 1003, unitName: "G", value: 31 },
      { nutrientId: 1004, unitName: "G", value: 3.6 },
      { nutrientId: 1005, unitName: "G", value: 0 },
    ]),
  );
  assert.equal(m?.calories, 160);
});

test("extractMacros falls back to 4P + 4C + 9F when no energy is reported", () => {
  const m = extractMacros(
    food([
      { nutrientId: 1003, unitName: "G", value: 10 },
      { nutrientId: 1004, unitName: "G", value: 2 },
      { nutrientId: 1005, unitName: "G", value: 20 },
    ]),
  );
  assert.equal(m?.calories, 4 * 10 + 4 * 20 + 9 * 2);
});

test("extractMacros returns null when a macro is missing", () => {
  const m = extractMacros(
    food([
      { nutrientId: 1008, unitName: "KCAL", value: 100 },
      { nutrientId: 1003, unitName: "G", value: 10 },
    ]),
  );
  assert.equal(m, null);
});

test("foodIdFor turns a search phrase into a safe Firestore id", () => {
  assert.equal(foodIdFor("Chicken Breast, roasted"), "chicken_breast_roasted");
  assert.equal(foodIdFor("  rice / white (cooked) "), "rice_white_cooked");
});

test("utcDayKey uses the UTC date", () => {
  // 11:30 pm EST on Nov 24 is already Nov 25 in UTC.
  assert.equal(utcDayKey(new Date("2026-11-25T04:30:00Z")), "2026-11-25");
});
