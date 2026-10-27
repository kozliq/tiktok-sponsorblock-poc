// Живая проверка API на локальном wrangler dev (npm run dev в соседнем окне).
// Каждый запуск берёт новый ролик; между запусками — минута, чтобы остыл лимит записи.
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";

const API = process.env.API ?? "http://127.0.0.1:8799";
const video = String(7300000000000000000n + BigInt(Math.floor(Math.random() * 1e15)));
const hash = createHash("sha256").update(video).digest("hex");
const user = () => randomBytes(16).toString("hex");
const [alice, bob, carol, dave, eve] = [user(), user(), user(), user(), user()];

async function call(method, path, body) {
	const res = await fetch(API + path, {
		method,
		headers: body ? { "Content-Type": "application/json" } : {},
		body: body ? JSON.stringify(body) : undefined,
	});
	return { status: res.status, data: await res.json(), headers: res.headers };
}
const mark = (userID, startMs, endMs, extra = {}) =>
	call("POST", "/api/segments", { videoID: video, userID, startMs, endMs, durationMs: 23000, category: "scam", ...extra });
const vote = (UUID, userID, type) => call("POST", "/api/vote", { UUID, userID, type });
const direct = () => call("GET", `/api/segments?videoID=${video}&t=${Date.now()}`);
let step = 0;
const ok = (name) => console.log(`✔ ${++step}. ${name}`);

// 1. Пустой ролик
let r = await call("GET", `/api/skip/v1/${hash.slice(0, 4)}?fresh=${Date.now()}`);
assert.equal(r.status, 200);
assert.ok(!r.data.some((v) => v.videoID === video));
assert.equal(r.headers.get("access-control-allow-origin"), "*");
ok("пустой ответ по префиксу, CORS на месте");

// 2. Тролль размечает весь ролик
r = await mark(eve, 0, 23000);
assert.equal(r.status, 400);
ok(`тролль «весь ролик» отбит: ${r.data.error}`);

// 3. Нормальная отметка
r = await mark(alice, 5000, 11200);
assert.equal(r.status, 201);
const first = r.data.UUID;
ok("отметка Алисы 0:05–0:11 принята");

// 4. Боб отмечает почти то же самое — это голос «за», не дубль
r = await mark(bob, 5300, 11000);
assert.equal(r.status, 200);
assert.equal(r.data.UUID, first);
assert.equal(r.data.merged, true);
ok("похожая отметка Боба слилась с Алисиной как голос «за»");

// 5. Алиса не может отметить то же второй раз
r = await mark(alice, 5000, 11000);
assert.equal(r.status, 409);
ok("повтор от того же человека — 409");

// 6. k-анонимное чтение: клиент шлёт 4 hex, фильтрует у себя по полному хэшу
r = await call("GET", `/api/skip/v1/${hash.slice(0, 4)}?t=${Date.now()}`);
const mine = r.data.find((v) => v.hash === hash);
assert.ok(mine, "ролик нашёлся по префиксу");
assert.deepEqual(mine.segments.map((s) => s.segment), [[5000, 11200]]);
assert.equal(mine.segments[0].votes, 1);
ok(`чтение по префиксу ${hash.slice(0, 4)}: ${r.data.length} ролик(ов) в корзине, наш с 1 голосом`);

// 7. Второй, перекрывающийся но другой отрезок от Кэрол — в выдаче остаётся лучший
r = await mark(carol, 9000, 14000, { category: "sponsor" });
assert.equal(r.status, 201);
const overlap = r.data.UUID;
r = await direct();
assert.deepEqual(r.data.map((s) => s.UUID), [first]);
ok("перекрывающийся отрезок с 0 голосов не вытеснил отрезок с 1 голосом");

// 8. Голоса против прячут отрезок Кэрол
await vote(overlap, alice, 0);
await vote(overlap, bob, 0);
r = await call("POST", "/api/segments", { videoID: video, userID: dave, startMs: 9100, endMs: 14000, durationMs: 23000, category: "scam" });
assert.equal(r.status, 409, "заминусованный отрезок нельзя протащить заново");
ok("−2 голоса скрыли отрезок, повторная попытка протащить его — 409");

// 9. Передумал: «против» → «за» меняет счёт на 2
await vote(first, dave, 0);
await vote(first, dave, 1);
r = await direct();
assert.equal(r.data[0].votes, 2);
ok("передуманный голос пересчитан правильно (итог +2)");

// 10. Мусор
assert.equal((await call("GET", "/api/skip/v1/zz")).status, 404);    // не hex — не наш адрес
assert.equal((await call("GET", "/api/skip/v1/abc")).status, 400);   // короче 4 — слишком мало для префикса
ok("кривые запросы отбиваются");

// Лимит WRITE_LIMIT — 10 записей в минуту с адреса. Счётчик Cloudflare приблизительный
// (копится в каждом дата-центре с задержкой), поэтому ждём 429 не ровно на 11-й, а в пределах серии.
// Кривой UUID — до базы такие запросы не доходят.
let tries = 0, limitedAt = 0;
while (tries < 40 && !limitedAt) {
	tries++;
	if ((await call("POST", "/api/vote", { UUID: "x", userID: alice, type: 1 })).status === 429) limitedAt = tries;
}
assert.ok(limitedAt, "лимит записи так и не сработал за 40 запросов");
ok(`лимит записи сработал: 429 на ${10 + limitedAt}-й записи за минуту (адрес не хранится)`);

// 11. Кэш: второй одинаковый запрос отдаётся из кэша Cloudflare
const url = `/api/skip/v1/${hash.slice(0, 5)}?cache=${Date.now()}`;
const a = await call("GET", url);
const b = await call("GET", url);
assert.match(b.headers.get("cache-control") ?? "", /s-maxage=60/);
assert.deepEqual(a.data, b.data);
ok("ответы кэшируются (s-maxage=60)");

r = await call("GET", "/api/status");
console.log(`\nВсё прошло. В базе: ${r.data.segments} отрезков, ${r.data.videos} роликов.`);
