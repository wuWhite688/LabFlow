import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

/** Keep in sync with app/lib/roles.ts navItemsForRole */
function navItemsForRole(role) {
  switch (role) {
    case "STUDENT":
      return ["dashboard", "equipment", "reservations", "workorders"];
    case "TEACHER":
      return ["dashboard", "equipment", "reservations", "workorders"];
    case "TECHNICIAN":
      return ["dashboard", "equipment", "workorders"];
    case "ADMIN":
    default:
      return ["dashboard", "equipment", "reservations", "workorders", "audit"];
  }
}

function isViewAllowed(role, view) {
  return navItemsForRole(role).includes(view);
}

test("server-renders LabFlow login shell", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /LabFlow/i);
  assert.match(html, /实验室设备运营平台|设备预约与维护|进入设备运行台|进入 LabFlow/);
  assert.doesNotMatch(html, /Your site is taking shape|react-loading-skeleton|Codex is building/i);
});

test("keeps LabFlow app source structure without starter skeleton", async () => {
  const [page, layout, packageJson, appEntries] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../package.json", import.meta.url), "utf8"),
    readdir(new URL("../app/", import.meta.url)),
  ]);

  assert.match(layout, /LabFlow/);
  assert.match(layout, /实验室设备运营平台/);
  assert.doesNotMatch(layout, /Starter Project|codex-preview|_sites-preview/);

  assert.match(page, /LoginView|export default function Home/);
  assert.match(page, /canAssignWorkOrders|WorkOrdersView|EquipmentView/);
  assert.match(page, /isViewAllowed|onOpenCreate/);
  assert.match(page, /loginRequest|saveTokens|clearTokens|logoutRequest/);
  assert.match(page, /subscribeTokens/);
  assert.doesNotMatch(page, /SkeletonPreview|codex-preview|sites-skeleton|authHeader|Basic /);


  assert.doesNotMatch(packageJson, /react-loading-skeleton/);
  assert.ok(appEntries.includes("components"));
  assert.ok(appEntries.includes("lib"));
  assert.ok(!appEntries.includes("_sites-preview"));
});

test("role navigation and dashboard copy are differentiated in source", async () => {
  const [rolesSrc, headerSrc, dashboardSrc] = await Promise.all([
    readFile(new URL("../app/lib/roles.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/components/AppHeader.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/components/views/DashboardView.tsx", import.meta.url), "utf8"),
  ]);

  assert.match(rolesSrc, /export function navItemsForRole/);
  assert.match(rolesSrc, /case "STUDENT"/);
  assert.match(rolesSrc, /case "TEACHER"/);
  assert.match(rolesSrc, /case "TECHNICIAN"/);
  assert.match(rolesSrc, /case "ADMIN"/);
  assert.match(rolesSrc, /我的工作台/);
  assert.match(rolesSrc, /教学工作台/);
  assert.match(rolesSrc, /维修工作台/);
  assert.match(rolesSrc, /运营总览/);
  assert.match(rolesSrc, /操作审计/);
  // Technician nav block must not include reservations entry
  const techBlock = rolesSrc.match(/case "TECHNICIAN":\s*return \[([\s\S]*?)\];/);
  assert.ok(techBlock, "TECHNICIAN nav block missing");
  assert.doesNotMatch(techBlock[1], /reservations/);
  assert.doesNotMatch(techBlock[1], /audit/);

  assert.match(headerSrc, /navItemsForRole/);
  assert.match(headerSrc, /data-role=\{user\.role\}/);
  assert.match(headerSrc, /workspaceEyebrow/);
  assert.match(headerSrc, /workspaceTitle/);

  assert.match(dashboardSrc, /data-dashboard-role=\{user\.role\}/);
  assert.match(dashboardSrc, /快速预约/);
  assert.match(dashboardSrc, /故障报修/);
  assert.match(dashboardSrc, /待审批预约/);
  assert.match(dashboardSrc, /近期排期/);
  assert.match(dashboardSrc, /设备管理/);
  assert.match(dashboardSrc, /待接单池/);
  assert.match(dashboardSrc, /我的处理中工单/);
  assert.match(dashboardSrc, /派单入口/);
  assert.match(dashboardSrc, /异常情况|异常与审计/);
  assert.match(dashboardSrc, /审计日志|打开审计/);
  assert.match(dashboardSrc, /quick-actions/);
  assert.match(dashboardSrc, /quick-card/);
  assert.match(dashboardSrc, /你还没有预约/);
  assert.match(dashboardSrc, /待接单池为空/);
  assert.match(dashboardSrc, /当前没有待审批预约/);
});

test("role helper contracts hide irrelevant navigation entries", () => {
  assert.deepEqual(navItemsForRole("STUDENT"), ["dashboard", "equipment", "reservations", "workorders"]);
  assert.equal(isViewAllowed("STUDENT", "audit"), false);

  assert.deepEqual(navItemsForRole("TEACHER"), ["dashboard", "equipment", "reservations", "workorders"]);
  assert.equal(isViewAllowed("TEACHER", "audit"), false);

  assert.deepEqual(navItemsForRole("TECHNICIAN"), ["dashboard", "equipment", "workorders"]);
  assert.equal(isViewAllowed("TECHNICIAN", "reservations"), false);
  assert.equal(isViewAllowed("TECHNICIAN", "audit"), false);

  assert.deepEqual(navItemsForRole("ADMIN"), ["dashboard", "equipment", "reservations", "workorders", "audit"]);
  assert.equal(isViewAllowed("ADMIN", "audit"), true);
});
