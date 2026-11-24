// Pure macro math, kept apart from index.ts so tests can import it without
// starting Firebase.
import type { Macros } from "./usda.js";

const round1 = (x: number) => Math.round(x * 10) / 10;

/** Macros for `grams` of a food: per100g * grams / 100, rounded to 0.1. */
export function scale(per100g: Macros, grams: number): Macros {
  const f = grams / 100;
  return {
    calories: round1(per100g.calories * f),
    protein: round1(per100g.protein * f),
    fat: round1(per100g.fat * f),
    carbs: round1(per100g.carbs * f),
  };
}

/** Plate totals. Rounds once at the end so per-item rounding errors don't pile up. */
export function sum(list: Macros[]): Macros {
  const t = list.reduce(
    (a, m) => ({
      calories: a.calories + m.calories,
      protein: a.protein + m.protein,
      fat: a.fat + m.fat,
      carbs: a.carbs + m.carbs,
    }),
    { calories: 0, protein: 0, fat: 0, carbs: 0 },
  );
  return { calories: round1(t.calories), protein: round1(t.protein), fat: round1(t.fat), carbs: round1(t.carbs) };
}
