// Per-user daily scan limit. Every scan is a paid Claude call, and anyone can
// create an account, so without a cap one user (or a script) could run up the
// Anthropic bill. Counts live at usage/{uid}/days/{yyyy-mm-dd}; Firestore rules
// give clients no access there, so only this function (Admin SDK) can change them.
import { getFirestore, FieldValue } from "firebase-admin/firestore";

export const DAILY_SCAN_LIMIT = 30;

/** "yyyy-mm-dd" in UTC. The limit resets at midnight UTC (7 pm EST). */
export function utcDayKey(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}

/**
 * Counts one scan for `uid` and returns true, or returns false if the user has
 * already used today's limit. A transaction makes the read-check-write atomic,
 * so two scans sent at the same moment can't both slip past the limit.
 *
 * The scan is counted before Claude runs, so failed scans count too. That is
 * on purpose: a failed call can still cost money.
 */
export async function tryConsumeScan(uid: string, limit: number = DAILY_SCAN_LIMIT): Promise<boolean> {
  const db = getFirestore();
  const ref = db.collection("usage").doc(uid).collection("days").doc(utcDayKey());
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const used = (snap.get("count") as number | undefined) ?? 0;
    if (used >= limit) return false;
    tx.set(ref, { count: used + 1, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return true;
  });
}
