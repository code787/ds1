/**
 * 手势引擎单测：方向映射 + 编辑距离匹配。
 *
 * 跑法：node --test band-app/test/gesture.test.js
 */
import { test } from "node:test";
import assert from "node:assert";

// ---- 与 GestureEngine.kt / gesture.js 一致的实现 ----
const DEAD_ZONE = 12;

function toDir(x, y) {
  const mag = Math.sqrt(x * x + y * y);
  if (mag < DEAD_ZONE) return null;
  const angle = Math.atan2(y, x) * 180 / Math.PI;
  const n = ((angle % 360) + 360) % 360;
  if (n < 22.5 || n >= 337.5) return "E";
  if (n < 67.5) return "NE";
  if (n < 112.5) return "N";
  if (n < 157.5) return "NW";
  if (n < 202.5) return "W";
  if (n < 247.5) return "SW";
  if (n < 292.5) return "S";
  return "SE";
}

function distance(a, b) {
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost);
    }
    prev = cur;
  }
  return prev[b.length];
}

function bestMatch(actual, templates, maxDistance = 1) {
  if (actual.length === 0 || templates.length === 0) return null;
  let best = null;
  let bestDist = 999;
  for (const g of templates) {
    const d = distance(actual, g.path);
    if (d < bestDist) { bestDist = d; best = g; }
  }
  return bestDist <= maxDistance ? best : null;
}

// ---- 方向映射 ----
test("死区内返回 null（防抖）", () => {
  assert.equal(toDir(0, 0), null);
  assert.equal(toDir(5, 5), null);
  assert.equal(toDir(8, -8), null);
});

test("八个基本方向映射正确", () => {
  assert.equal(toDir(50, 0), "E");
  assert.equal(toDir(0, 50), "N");
  assert.equal(toDir(-50, 0), "W");
  assert.equal(toDir(0, -50), "S");
  assert.equal(toDir(35, 35), "NE");
  assert.equal(toDir(-35, 35), "NW");
  assert.equal(toDir(-35, -35), "SW");
  assert.equal(toDir(35, -35), "SE");
});

test("角度边界不越界", () => {
  // 22.5 度附近应该落在 E 或 NE
  const d = toDir(100, 41);
  assert.ok(d === "E" || d === "NE", "得到 " + d);
});

// ---- 编辑距离 ----
test("完全相同的序列距离为 0", () => {
  assert.equal(distance(["E", "S"], ["E", "S"]), 0);
});

test("少一个方向距离为 1", () => {
  assert.equal(distance(["E"], ["E", "S"]), 1);
});

test("多一个抖动距离为 1", () => {
  assert.equal(distance(["E", "S", "S"], ["E", "S"]), 1);
});

test("完全不同距离为 2", () => {
  assert.equal(distance(["N", "N"], ["S", "S"]), 2);
});

// ---- 匹配 ----
const templates = [
  { id: "1", name: "返回", path: ["E", "S"] },
  { id: "2", name: "主页", path: ["N", "N"] },
  { id: "3", name: "多任务", path: ["W"] },
];

test("精确匹配", () => {
  const hit = bestMatch(["E", "S"], templates);
  assert.equal(hit.name, "返回");
});

test("容错：漏一个方向仍能匹配", () => {
  const hit = bestMatch(["E"], templates);
  assert.equal(hit.name, "返回");
});

test("容错：多一个抖动仍能匹配", () => {
  const hit = bestMatch(["N", "N", "N"], templates);
  assert.equal(hit.name, "主页");
});

test("差太多时返回 null，不猜", () => {
  // 注意：["S","S"] 到 ["E","S"] 只差一位，距离 1，按容错规则会匹配到"返回"。
  // 这符合设计——真人画手势不可能分毫不差。
  // 真正"差太多"的是完全不同的方向序列，比如 ["SW","SE","NE"]。
  assert.equal(bestMatch(["SW", "SE", "NE"], templates), null);
});

test("距离 1 内会匹配（这是有意为之的容错）", () => {
  // 记录这个行为，避免以后误以为它是 bug
  const hit = bestMatch(["S", "S"], templates);
  assert.equal(hit.name, "返回");
});

test("空输入返回 null", () => {
  assert.equal(bestMatch([], templates), null);
  assert.equal(bestMatch(["E"], []), null);
});