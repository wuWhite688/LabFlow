import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";

async function pageSource() {
  return readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
}

test("session changes abort requests and clear every role-scoped dataset", async () => {
  const source = await pageSource();
  const resetBlock = source.match(
    /const resetUserScopedState = useCallback\(\(\) => \{[\s\S]*?\n  \}, \[\]\);/,
  )?.[0];

  assert.ok(resetBlock, "resetUserScopedState implementation not found");
  assert.match(resetBlock, /sessionGeneration\.current \+= 1/);
  assert.match(resetBlock, /controller\.abort\(\)/);
  for (const setter of [
    "setStats(null)",
    "setEquipment(null)",
    "setReservations(null)",
    "setScheduleReservations(null)",
    "setWorkorders(null)",
    "setAuditLogs(null)",
    "setTechnicians([])",
  ]) {
    assert.ok(resetBlock.includes(setter), `missing user-state cleanup: ${setter}`);
  }

  assert.match(source, /if \(!session\) \{[\s\S]*?resetUserScopedState\(\);[\s\S]*?setUser\(null\)/);
  assert.match(source, /sessionPrincipal\.current !== nextPrincipal[\s\S]*?resetUserScopedState\(\)/);
});

test("late page responses are gated by the current session generation", async () => {
  const source = await pageSource();
  const loadBlock = source.match(
    /const load = useCallback\(async[\s\S]*?\n  \}, \[accessToken, reservationFilter, workorderFilter, notify\]\);/,
  )?.[0];

  assert.ok(loadBlock, "load implementation not found");
  assert.match(loadBlock, /const generation = sessionGeneration\.current/);
  assert.match(loadBlock, /const controller = new AbortController\(\)/);
  assert.match(loadBlock, /generation === sessionGeneration\.current && !controller\.signal\.aborted/);
  assert.match(loadBlock, /api<Stats>\([^\n]+signal: controller\.signal/);
  assert.match(loadBlock, /if \(!isCurrentSessionRequest\(\)\) return;[\s\S]*?setStats\(statsData\)/);
  assert.match(loadBlock, /if \(isCurrentSessionRequest\(\)\) setWorkorders\(workorderData\)/);
  assert.match(loadBlock, /if \(isCurrentSessionRequest\(\)\) setAuditLogs\(auditData\)/);
});

test("failed mutations keep form contents", async () => {
  const source = await pageSource();
  const mutateBlock = source.match(
    /async function mutate[\s\S]*?\r?\n  \}\r?\n\r?\n  async function submitEquipment/,
  )?.[0];

  assert.ok(mutateBlock, "mutate implementation not found");
  assert.match(mutateBlock, /Promise<boolean>/);
  assert.match(mutateBlock, /catch \(error\)[\s\S]*?return false/);
  assert.match(source, /if \(await mutate\("\/api\/equipment"[\s\S]*?setEquipmentForm\(emptyEquipmentForm\)/);
  assert.match(source, /if \(await mutate\("\/api\/reservations"[\s\S]*?setReservationForm\(emptyReservationForm\)/);
  assert.match(source, /if \(await mutate\("\/api\/work-orders"[\s\S]*?setWorkorderForm\(emptyWorkorderForm\)/);
});
