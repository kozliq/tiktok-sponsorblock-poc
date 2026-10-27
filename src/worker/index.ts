// Мимо API: таймкоды вшитой рекламы в роликах TikTok.
//
//   GET  /api/skip/v1/{prefix}   k-анонимное чтение: prefix = первые 4–64 hex sha256(aweme_id)
//   GET  /api/segments?videoID=  прямое чтение одного ролика (для бота Качалки — он ID и так знает)
//   POST /api/segments           новая отметка {videoID, userID, startMs, endMs, durationMs, category}
//   POST /api/vote               голос {UUID, userID, type: 1 «за» | 0 «против»}
//   GET  /api/status             цифры для сайта и сторожа

import {
	CATEGORIES, HIDE_AT, SHADOWBAN_AT,
	decide, pickSegments, validateSubmission,
	type Segment, type Submission,
} from "./consensus.ts";

export interface Env {
	DB: D1Database;
	READ_LIMIT?: RateLimit;
	WRITE_LIMIT?: RateLimit;
	PEPPER?: string;
	BOT_KEY?: string;
	MAX_SEGMENT_MS?: string;
	CACHE_SECONDS?: string;
}

const HEX = /^[0-9a-f]{4,64}$/;
const MAX_BODY = 2048;
const DAILY_SUBMITS = 60;

const CORS = {
	"Access-Control-Allow-Origin": "*",
	"Access-Control-Allow-Methods": "GET, POST, OPTIONS",
	"Access-Control-Allow-Headers": "Content-Type",  // X-Mimo-* шлёт только сервер бота, браузерам они не нужны
	"Access-Control-Max-Age": "86400",
};

function json(data: unknown, status = 200, extra: Record<string, string> = {}): Response {
	return new Response(JSON.stringify(data), {
		status,
		headers: {
			"Content-Type": "application/json; charset=utf-8",
			"X-Content-Type-Options": "nosniff",
			...CORS,
			...extra,
		},
	});
}

const fail = (status: number, error: string) => json({ error }, status, { "Cache-Control": "no-store" });

async function sha256hex(text: string): Promise<string> {
	const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
	return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** userID в базу не попадает: только HMAC с секретом, который живёт в секретах Worker. */
async function userHash(env: Env, userID: string): Promise<string> {
	if (!env.PEPPER) throw new Error("PEPPER не задан: npx wrangler secret put PEPPER");
	const key = await crypto.subtle.importKey(
		"raw", new TextEncoder().encode(env.PEPPER), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
	);
	const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(userID));
	return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Ключ счётчика — адрес, но только в памяти Cloudflare: в D1 и журналы он не пишется.
 * Бот Качалки шлёт отметки тысяч людей с одного адреса, поэтому ему, по секрету BOT_KEY,
 * счётчик ведётся по человеку (заголовок X-Mimo-User), а не по адресу.
 */
async function limited(limiter: RateLimit | undefined, req: Request, env: Env): Promise<boolean> {
	if (!limiter) return false;
	const bot = env.BOT_KEY && req.headers.get("X-Mimo-Bot") === env.BOT_KEY;
	const key = bot ? `bot:${req.headers.get("X-Mimo-User") ?? ""}` : (req.headers.get("CF-Connecting-IP") ?? "local");
	const { success } = await limiter.limit({ key });
	return !success;
}

async function readBody<T>(req: Request): Promise<Partial<T> | null> {
	const text = await req.text();
	if (text.length > MAX_BODY) return null;
	try {
		const v = JSON.parse(text);
		return v && typeof v === "object" ? v : null;
	} catch {
		return null;
	}
}

type Row = Segment & { video_id: string; video_hash: string; duration_ms: number };

function present(rows: Row[]) {
	const byVideo = new Map<string, Row[]>();
	for (const r of rows) {
		const list = byVideo.get(r.video_id) ?? [];
		list.push(r);
		byVideo.set(r.video_id, list);
	}
	return [...byVideo.entries()].map(([videoID, list]) => ({
		videoID,
		hash: list[0].video_hash,
		segments: (pickSegments(list) as Row[]).map((s) => ({
			UUID: s.uuid,
			segment: [s.start_ms, s.end_ms],
			category: s.category,
			votes: s.votes,
			locked: s.locked === 1,
			videoDuration: s.duration_ms,
		})),
	}));
}

const SELECT = `SELECT uuid, video_id, video_hash, start_ms, end_ms, duration_ms, category, votes, locked, created_at
                FROM segments`;

/** Вирусный ролик смотрят миллионы: ответ по префиксу кладём в кэш дата-центра Cloudflare. */
async function cached(req: Request, env: Env, ctx: ExecutionContext, build: () => Promise<Response>) {
	const cache = caches.default;
	const key = new Request(new URL(req.url).toString(), { method: "GET" });
	const hit = await cache.match(key);
	if (hit) return hit;
	const res = await build();
	if (res.status === 200) {
		const ttl = Number(env.CACHE_SECONDS ?? 60);
		res.headers.set("Cache-Control", `public, max-age=${ttl}, s-maxage=${ttl}`);
		ctx.waitUntil(cache.put(key, res.clone()));
	}
	return res;
}

async function skipByPrefix(prefix: string, env: Env): Promise<Response> {
	const { results } = await env.DB.prepare(`${SELECT} WHERE prefix4 = ? AND hidden = 0`)
		.bind(prefix.slice(0, 4))
		.all<Row>();
	const rows = results.filter((r) => r.video_hash.startsWith(prefix));
	// Пустой ответ — тоже 200 и тоже в кэш: иначе каждый новый ролик бьёт в D1.
	return json(present(rows));
}

async function segmentsOf(videoID: string, env: Env): Promise<Response> {
	const { results } = await env.DB.prepare(`${SELECT} WHERE video_id = ? AND hidden = 0`).bind(videoID).all<Row>();
	return json(present(results)[0]?.segments ?? []);
}

async function submit(req: Request, env: Env): Promise<Response> {
	const body = await readBody<Submission>(req);
	if (!body) return fail(400, "нужен JSON до 2 КБ");
	const problem = validateSubmission(body, Number(env.MAX_SEGMENT_MS ?? 60_000));
	if (problem) return fail(400, problem);
	const s = body as Submission;

	const who = await userHash(env, s.userID);
	const now = Date.now();
	const db = env.DB;

	const today = await db.prepare(`SELECT COUNT(*) AS n FROM segments WHERE submitter_hash = ? AND created_at > ?`)
		.bind(who, now - 86_400_000).first<{ n: number }>();
	if ((today?.n ?? 0) >= DAILY_SUBMITS) return fail(429, "на сегодня отметок хватит");

	await db.prepare(`INSERT OR IGNORE INTO users (user_hash, created_at) VALUES (?, ?)`).bind(who, now).run();
	const user = await db.prepare(`SELECT shadowbanned FROM users WHERE user_hash = ?`).bind(who).first<{ shadowbanned: number }>();

	const { results: existing } = await db.prepare(
		`SELECT uuid, start_ms, end_ms, category, votes, locked, created_at, hidden, submitter_hash
		 FROM segments WHERE video_id = ?`,
	).bind(s.videoID).all<Segment & { hidden: number; submitter_hash: string }>();

	const d = decide({ start_ms: s.startMs, end_ms: s.endMs, category: s.category, submitter_hash: who }, existing);
	if (d.kind === "reject") return fail(409, d.reason);
	if (d.kind === "upvote") {
		await vote(env, d.uuid, who, 1);
		return json({ UUID: d.uuid, merged: true }, 200);
	}

	const uuid = crypto.randomUUID();
	const hash = await sha256hex(s.videoID);
	await db.prepare(
		`INSERT INTO segments (uuid, video_id, video_hash, prefix4, start_ms, end_ms, duration_ms, category,
		                       submitter_hash, hidden, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
	).bind(uuid, s.videoID, hash, hash.slice(0, 4), s.startMs, s.endMs, s.durationMs, s.category,
		who, user?.shadowbanned ? 1 : 0, now).run();
	return json({ UUID: uuid, merged: false }, 201);
}

/**
 * Голос. Пересчёт идёт одним batch (D1 выполняет его транзакцией):
 * голос → счётчики отрезка → скрытие → репутация автора → теневой бан.
 */
async function vote(env: Env, uuid: string, voter: string, value: 1 | -1): Promise<{ status: number; error?: string }> {
	const db = env.DB;
	const seg = await db.prepare(`SELECT submitter_hash, locked FROM segments WHERE uuid = ?`)
		.bind(uuid).first<{ submitter_hash: string; locked: number }>();
	if (!seg) return { status: 404, error: "нет такого отрезка" };

	const banned = await db.prepare(`SELECT shadowbanned FROM users WHERE user_hash = ?`).bind(voter).first<{ shadowbanned: number }>();
	if (banned?.shadowbanned) return { status: 200 };   // молча: голос забаненного ничего не меняет

	// Автор сам жмёт «против» — значит, ошибся: прячем сразу.
	if (seg.submitter_hash === voter) {
		if (value === -1) await db.prepare(`UPDATE segments SET hidden = 1 WHERE uuid = ? AND locked = 0`).bind(uuid).run();
		return { status: 200 };
	}

	const prev = await db.prepare(`SELECT vote FROM votes WHERE segment_uuid = ? AND voter_hash = ?`)
		.bind(uuid, voter).first<{ vote: number }>();
	if (prev?.vote === value) return { status: 200 };
	const delta = value - (prev?.vote ?? 0);           // ±1 за новый голос, ±2 за передуманный
	const up = value === 1 ? 1 : prev ? -1 : 0;
	const down = value === -1 ? 1 : prev ? -1 : 0;

	await db.batch([
		db.prepare(`INSERT INTO votes (segment_uuid, voter_hash, vote, created_at) VALUES (?, ?, ?, ?)
		            ON CONFLICT (segment_uuid, voter_hash) DO UPDATE SET vote = excluded.vote, created_at = excluded.created_at`)
			.bind(uuid, voter, value, Date.now()),
		db.prepare(`UPDATE segments SET votes = votes + ?, upvotes = upvotes + ?, downvotes = downvotes + ?,
		              hidden = CASE WHEN locked = 0 AND votes + ? <= ? THEN 1 ELSE hidden END
		            WHERE uuid = ?`)
			.bind(delta, up, down, delta, HIDE_AT, uuid),
		db.prepare(`UPDATE users SET reputation = reputation + ?,
		              shadowbanned = CASE WHEN reputation + ? <= ? THEN 1 ELSE shadowbanned END
		            WHERE user_hash = ?`)
			.bind(delta, delta, SHADOWBAN_AT, seg.submitter_hash),
		// Попал в теневой бан — прячем все его незакреплённые отрезки без явной поддержки.
		db.prepare(`UPDATE segments SET hidden = 1
		            WHERE submitter_hash = ? AND locked = 0 AND votes <= 0
		              AND (SELECT shadowbanned FROM users WHERE user_hash = ?) = 1`)
			.bind(seg.submitter_hash, seg.submitter_hash),
	]);
	return { status: 200 };
}

async function voteRoute(req: Request, env: Env): Promise<Response> {
	const body = await readBody<{ UUID: string; userID: string; type: number }>(req);
	if (!body) return fail(400, "нужен JSON до 2 КБ");
	if (typeof body.UUID !== "string" || !/^[0-9a-f-]{36}$/.test(body.UUID)) return fail(400, "UUID");
	if (typeof body.userID !== "string" || !/^[A-Za-z0-9_-]{16,64}$/.test(body.userID)) return fail(400, "userID");
	if (body.type !== 0 && body.type !== 1) return fail(400, "type: 1 — за, 0 — против");
	const r = await vote(env, body.UUID, await userHash(env, body.userID), body.type === 1 ? 1 : -1);
	return r.error ? fail(r.status, r.error) : json({ ok: true });
}

async function status(env: Env): Promise<Response> {
	const row = await env.DB.prepare(
		`SELECT COUNT(*) AS segments, COUNT(DISTINCT video_id) AS videos,
		        COALESCE(SUM(end_ms - start_ms), 0) AS ms FROM segments WHERE hidden = 0`,
	).first<{ segments: number; videos: number; ms: number }>();
	return json({ ...row, categories: CATEGORIES });
}

export default {
	async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
		const url = new URL(req.url);
		const path = url.pathname;
		if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });

		try {
			if (req.method === "GET") {
				if (await limited(env.READ_LIMIT, req, env)) return fail(429, "слишком часто");
				const m = path.match(/^\/api\/skip\/v1\/([0-9a-fA-F]+)$/);
				if (m) {
					const prefix = m[1].toLowerCase();
					if (!HEX.test(prefix)) return fail(400, "префикс: 4–64 hex-символа sha256(videoID)");
					return cached(req, env, ctx, () => skipByPrefix(prefix, env));
				}
				if (path === "/api/segments") {
					const id = url.searchParams.get("videoID") ?? "";
					if (!/^\d{8,20}$/.test(id)) return fail(400, "videoID");
					return cached(req, env, ctx, () => segmentsOf(id, env));
				}
				if (path === "/api/status") return cached(req, env, ctx, () => status(env));
				if (path === "/") return json({ name: "Мимо API", docs: "https://github.com/kozliq/mimo" });
			}
			if (req.method === "POST") {
				if (await limited(env.WRITE_LIMIT, req, env)) return fail(429, "слишком часто");
				if (path === "/api/segments") return await submit(req, env);
				if (path === "/api/vote") return await voteRoute(req, env);
			}
			return fail(404, "нет такого адреса");
		} catch (e) {
			console.error(e);
			return fail(500, "сервер споткнулся");
		}
	},
} satisfies ExportedHandler<Env>;
