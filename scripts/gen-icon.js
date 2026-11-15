#!/usr/bin/env node
/**
 * 生成手环端占位图标 logo.png（192x192）。
 *
 * 不依赖任何图形库：手写最小 PNG 编码器。
 * 图标是深色底 + 绿色圆环，呼应"手环遥控"。
 */
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

const SIZE = 192;
const px = Buffer.alloc(SIZE * SIZE * 4);

function set(x, y, r, g, b, a = 255) {
  if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
  const i = (y * SIZE + x) * 4;
  px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = a;
}

const cx = SIZE / 2, cy = SIZE / 2;

for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    const d = Math.hypot(x - cx, y - cy);
    // 外圈圆角底
    if (d > SIZE * 0.46) { set(x, y, 0, 0, 0, 0); continue; }
    // 深色背景
    set(x, y, 17, 17, 17, 255);
    // 绿色圆环
    if (d > SIZE * 0.30 && d < SIZE * 0.38) set(x, y, 0, 255, 153, 255);
    // 中心圆点
    if (d < SIZE * 0.07) set(x, y, 0, 255, 153, 255);
  }
}

// PNG 编码
const raw = Buffer.alloc((SIZE * 4 + 1) * SIZE);
for (let y = 0; y < SIZE; y++) {
  raw[y * (SIZE * 4 + 1)] = 0; // filter: none
  px.copy(raw, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const t = Buffer.from(type, "ascii");
  const body = Buffer.concat([t, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body) >>> 0);
  return Buffer.concat([len, body, crc]);
}

let table = null;
function crc32(buf) {
  if (!table) {
    table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return c ^ -1;
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(SIZE, 0);
ihdr.writeUInt32BE(SIZE, 4);
ihdr[8] = 8;   // bit depth
ihdr[9] = 6;   // color type RGBA
ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;

const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk("IHDR", ihdr),
  chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
  chunk("IEND", Buffer.alloc(0)),
]);

const out = path.resolve(__dirname, "..", "band-app", "src", "logo.png");
fs.writeFileSync(out, png);
console.log("[ok] 图标已生成: " + out + " (" + png.length + " 字节)");
