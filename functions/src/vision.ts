// Step 1 of the route: Claude looks at the photo and says WHAT is on the plate
// and roughly HOW MUCH. It does NOT produce macros; USDA does that (see usda.ts).
import Anthropic from "@anthropic-ai/sdk";
import { betaZodOutputFormat } from "@anthropic-ai/sdk/helpers/beta/zod";
import { z } from "zod";

export const MODEL = "claude-opus-5-5";

export const DetectedFoodSchema = z.object({
  name: z.string().describe("Short display name, e.g. 'Grilled chicken breast'"),
  usda_query: z
    .string()
    .describe(
      "Generic USDA-style search phrase: food + preparation, no brands. " +
        "e.g. 'chicken breast roasted', 'rice white cooked', 'broccoli boiled'",
    ),
  grams_est: z.number().describe("Estimated edible weight on the plate, in grams"),
  confidence: z.enum(["low", "medium", "high"]),
});

const PlateSchema = z.object({
  is_food: z.boolean().describe("false if the photo does not show a meal"),
  items: z.array(DetectedFoodSchema),
});

export type DetectedFood = z.infer<typeof DetectedFoodSchema>;
export type MediaType = "image/jpeg" | "image/png" | "image/webp";

const SYSTEM_PROMPT = `You identify foods in meal photos for a nutrition-tracking app.
List each distinct food component separately (e.g. a burrito bowl becomes rice, beans, chicken, salsa, cheese).
Estimate the edible weight of each component in grams using visual cues such as plate size (assume a ~26 cm dinner plate unless something suggests otherwise), utensils, and typical serving sizes.
Write usda_query as a generic USDA FoodData Central search phrase: the base food plus its cooking method. Never use brand names.
Include sauces, dressings, and cooking oil only when they are clearly visible.
If the image is not a meal, set is_food to false and return no items.`;

export async function detectFoods(
  client: Anthropic,
  imageBase64: string,
  mediaType: MediaType,
): Promise<DetectedFood[]> {
  const response = await client.beta.messages.parse({
    model: MODEL,
    max_tokens: 4000,
    // If a safety classifier ever declines (rare for food photos), the API
    // retries server-side on Anthropic's recommended fallback model.
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    // Low effort keeps latency down; recognition doesn't need deep reasoning.
    output_config: { effort: "low", format: betaZodOutputFormat(PlateSchema) },
    system: SYSTEM_PROMPT,
    messages: [
      {
        role: "user",
        content: [
          { type: "image", source: { type: "base64", media_type: mediaType, data: imageBase64 } },
          { type: "text", text: "Identify every food on this plate with estimated grams." },
        ],
      },
    ],
  });

  if (response.stop_reason === "refusal") {
    throw new Error("The model declined to analyze this image.");
  }
  const plate = response.parsed_output;
  if (!plate) throw new Error("Could not parse the model's answer.");
  if (!plate.is_food) return [];
  return plate.items.filter((i) => i.grams_est > 0);
}
