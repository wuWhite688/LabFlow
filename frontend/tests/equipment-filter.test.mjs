import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("equipment search stays manual and ignores stale responses", async () => {
  const pageSrc = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");

  assert.match(pageSrc, /const appliedEquipmentFilter = useRef\(equipmentFilter\)/);
  assert.match(pageSrc, /const equipmentRequestSequence = useRef\(0\)/);
  assert.match(pageSrc, /const filter = appliedEquipmentFilter\.current/);
  assert.match(pageSrc, /requestSequence === equipmentRequestSequence\.current/);

  const loadDependencies = pageSrc.match(
    /}, \[accessToken, ([^\]]+)\]\);\s+\/\/ Keep React accessToken/,
  );
  assert.ok(loadDependencies, "load callback dependency list not found");
  assert.doesNotMatch(
    loadDependencies[1],
    /equipmentFilter/,
    "typing in the equipment filter must not recreate load() and retrigger the view effect",
  );

  assert.match(
    pageSrc,
    /function applyEquipmentFilter\(\) \{\s+appliedEquipmentFilter\.current = equipmentFilter;\s+void load\("equipment"\);\s+\}/,
  );
  assert.match(pageSrc, /onLoad=\{applyEquipmentFilter\}/);
});
