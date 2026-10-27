// Случайные проверки логики: тысячи сгенерированных отметок, у каждой — свойства, которые
// должны держаться всегда. Генератор с зерном: упавший случай повторяется по FUZZ_SEED.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
	validateSubmission, pickSegments, mergeIntervals, decide, iou, overlapMs, wilson,
	HIDE_AT, MAX_SHARE, MERGE_IOU, type Segment,
} from "../src/consensus.ts";

const N = Number(process.env.FUZZ_N ?? 5000);
let seed = Number(process.env.FUZZ_SEED ?? 20261026);
const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
const int = (a: number, b: number) => a + Math.floor(rnd() * (b - a + 1));
const pick = <T,>(xs: readonly T[]) => xs[int(0, xs.length - 1)];

function randomSegment(i: number, dur: number): Segment & { hidden: number; submitter_hash: string } {
	const start = int(0, dur - 1000);
	const end = Math.min(dur, start + int(1, 20000));
	return {
		uuid: `s${i}`, start_ms: start, end_ms: Math.max(end, start + 1), category: pick(["scam", "sponsor", "intro"]),
		votes: int(-4, 6), locked: rnd() < 0.1 ? 1 : 0, created_at: int(0, 1e6),
		hidden: rnd() < 0.2 ? 1 : 0, submitter_hash: pick(["A", "B", "C", "D", "E"]),
	};
}

test(`validateSubmission: ${N} случайных отметок — принятые всегда честные`, () => {
	let accepted = 0;
	for (let i = 0; i < N; i++) {
		const dur = int(-1000, 700_000);
		const s = {
			videoID: rnd() < 0.95 ? String(int(1e7, 9e7)) + "0123456789" : pick(["", "abc", "1".repeat(25), "12 34"]),
			userID: rnd() < 0.95 ? "u".repeat(int(16, 64)) : pick(["", "short", "x".repeat(65), "плохой_юзер_1234567"]),
			startMs: rnd() < 0.97 ? int(-5000, dur + 5000) : pick([1.5, NaN, Infinity, -0]),
			endMs: int(-5000, dur + 70_000),
			durationMs: dur,
			category: rnd() < 0.95 ? pick(["sponsor", "scam", "selfpromo", "intro"]) : pick(["music", "", "SCAM"]),
		};
		if (validateSubmission(s) !== null) continue;
		accepted++;
		const len = s.endMs - s.startMs;
		assert.ok(Number.isSafeInteger(s.startMs) && s.startMs >= 0, `start ${s.startMs}`);
		assert.ok(len >= 1000 && len <= 60_000, `длина ${len}`);
		assert.ok(len <= s.durationMs * MAX_SHARE, `больше половины: ${len} из ${s.durationMs}`);
		assert.ok(s.endMs <= s.durationMs + 500, "за концом ролика");
		assert.ok(s.durationMs >= 3000 && s.durationMs <= 600_000);
		assert.match(s.videoID, /^\d{8,20}$/);
	}
	assert.ok(accepted > N / 20, `слишком мало принятых для проверки: ${accepted}`);
});

test(`pickSegments: ${N} случайных роликов — выдача без пересечений и без мусора`, () => {
	for (let i = 0; i < N; i++) {
		const dur = int(5000, 120_000);
		const all = Array.from({ length: int(0, 12) }, (_, j) => randomSegment(j, dur));
		const out = pickSegments(all);
		for (let k = 1; k < out.length; k++) {
			assert.ok(out[k - 1].start_ms <= out[k].start_ms, "не отсортировано");
			assert.equal(overlapMs(out[k - 1], out[k]), 0, "пересекаются");
		}
		for (const s of out) {
			assert.ok(all.includes(s as never), "выдуманный отрезок");
			assert.ok(s.locked || s.votes > HIDE_AT, "заминусованный попал в выдачу");
		}
		// каждый отброшенный живой отрезок перекрыт выбранным, который не хуже его
		for (const s of all) {
			if (out.includes(s) || (!s.locked && s.votes <= HIDE_AT)) continue;
			const winner = out.find((c) => overlapMs(c, s) > 0);
			assert.ok(winner, `живой отрезок ${s.uuid} выброшен без причины`);
			assert.ok(winner!.locked > s.locked || (winner!.locked === s.locked && winner!.votes >= s.votes) ||
				out.some((c) => overlapMs(c, s) > 0 && (c.locked >= s.locked)), "победил худший");
		}
		// одинаковый вход — одинаковый ответ (кэш Cloudflare раздаёт одно и то же всем)
		assert.deepEqual(pickSegments([...all].reverse()).map((s) => s.uuid), out.map((s) => s.uuid));
	}
});

test(`mergeIntervals: ${N} случайных наборов — покрытие то же, пересечений нет`, () => {
	for (let i = 0; i < N; i++) {
		const list: [number, number][] = Array.from({ length: int(0, 10) }, () => {
			const s = int(0, 60_000);
			return [s, s + int(1, 8000)];
		});
		const out = mergeIntervals(list, 300);
		for (let k = 1; k < out.length; k++) assert.ok(out[k][0] > out[k - 1][1] + 300, "не склеено");
		for (const [s, e] of list) assert.ok(out.some(([a, b]) => a <= s && e <= b), "кусок потерян");
		for (const [a, b] of out) assert.ok(list.some(([s]) => s === a) && list.some(([, e]) => e === b), "граница выдумана");
	}
});

test(`decide: ${N} случайных отметок — дубль, повтор и протаскивание отклонённого`, () => {
	for (let i = 0; i < N; i++) {
		const dur = int(5000, 60_000);
		const existing = Array.from({ length: int(0, 6) }, (_, j) => randomSegment(j, dur));
		const start = int(0, dur - 1000);
		const sub = { start_ms: start, end_ms: start + int(1000, 15_000), category: "scam", submitter_hash: pick(["A", "B", "Z"]) };
		const d = decide(sub, existing);
		const mineOverlap = existing.some((e) => e.submitter_hash === sub.submitter_hash && overlapMs(e, sub) > 0);
		if (mineOverlap) { assert.equal(d.kind, "reject"); continue; }
		const best = existing.reduce((b, e) => (iou(e, sub) > (b ? iou(b, sub) : 0) ? e : b), null as (typeof existing)[number] | null);
		if (!best || iou(best, sub) < MERGE_IOU) assert.equal(d.kind, "insert");
		else if (best.hidden) assert.equal(d.kind, "reject");
		else assert.deepEqual(d, { kind: "upvote", uuid: best.uuid });
	}
});

test(`iou и wilson: ${N} случайных пар — в границах`, () => {
	for (let i = 0; i < N; i++) {
		const a = { start_ms: int(0, 1000), end_ms: 0 }, b = { start_ms: int(0, 1000), end_ms: 0 };
		a.end_ms = a.start_ms + int(1, 1000); b.end_ms = b.start_ms + int(1, 1000);
		const v = iou(a, b);
		assert.ok(v >= 0 && v <= 1 && Math.abs(v - iou(b, a)) < 1e-12);
		const up = int(0, 500), down = int(0, 500);
		const w = wilson(up, down);
		assert.ok(w >= 0 && w <= 1 && (up + down === 0 || w <= up / (up + down) + 1e-12));
	}
});
