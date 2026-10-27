import { test } from "node:test";
import assert from "node:assert/strict";
import { validateSubmission, iou, pickSegments, mergeIntervals, decide, wilson, type Segment } from "../src/consensus.ts";

const ok = { videoID: "7301234567890123456", userID: "u".repeat(32), startMs: 5000, endMs: 11000, durationMs: 22000, category: "scam" };
const seg = (uuid: string, s: number, e: number, votes = 0, extra: Partial<Segment> = {}): Segment =>
	({ uuid, start_ms: s, end_ms: e, category: "sponsor", votes, locked: 0, created_at: 0, ...extra });

test("нормальная отметка проходит", () => assert.equal(validateSubmission(ok), null));

test("тролль «весь ролик» отсекается", () => {
	assert.match(validateSubmission({ ...ok, startMs: 0, endMs: 15000, durationMs: 15000 })!, /половины/);
	assert.match(validateSubmission({ ...ok, startMs: 0, endMs: 12000, durationMs: 22000 })!, /половины/);
});

test("мусор во входе", () => {
	assert.ok(validateSubmission({ ...ok, videoID: "abc" }));
	assert.ok(validateSubmission({ ...ok, userID: "short" }));
	assert.ok(validateSubmission({ ...ok, category: "music" }));
	assert.ok(validateSubmission({ ...ok, startMs: 11000, endMs: 5000 }));
	assert.ok(validateSubmission({ ...ok, endMs: 5500 }));               // короче секунды
	assert.ok(validateSubmission({ ...ok, endMs: 30000 }));              // за концом ролика
	assert.ok(validateSubmission({ ...ok, startMs: 1.5 }));
	assert.ok(validateSubmission({ ...ok, durationMs: 700000 }));
});

test("IoU", () => {
	assert.equal(iou(seg("a", 0, 10), seg("b", 0, 10)), 1);
	assert.equal(iou(seg("a", 0, 10), seg("b", 10, 20)), 0);
	assert.equal(iou(seg("a", 0, 10), seg("b", 5, 15)), 5 / 15);
});

test("из перекрывающихся выбирается лучший, заминусованные не показываются", () => {
	const picked = pickSegments([
		seg("weak", 5000, 11000, 0),
		seg("strong", 5200, 11200, 3),
		seg("other", 14000, 18000, 1),
		seg("trash", 1000, 3000, -2),
	]);
	assert.deepEqual(picked.map((s) => s.uuid), ["strong", "other"]);
});

test("закреплённый важнее голосов", () => {
	const picked = pickSegments([seg("votes", 0, 5000, 10), seg("locked", 1000, 6000, -3, { locked: 1 })]);
	assert.deepEqual(picked.map((s) => s.uuid), ["locked"]);
});

test("склейка интервалов", () => {
	assert.deepEqual(mergeIntervals([[8000, 12000], [1000, 3000], [2900, 5000], [12100, 13000]]), [[1000, 5000], [8000, 13000]]);
});

test("почти та же отметка — голос «за», а не дубль", () => {
	const existing = [{ ...seg("x", 5000, 11000), hidden: 0, submitter_hash: "A" }];
	assert.deepEqual(decide({ start_ms: 5300, end_ms: 11100, category: "scam", submitter_hash: "B" }, existing), { kind: "upvote", uuid: "x" });
	assert.equal(decide({ start_ms: 5300, end_ms: 11100, category: "scam", submitter_hash: "A" }, existing).kind, "reject");
	assert.equal(decide({ start_ms: 14000, end_ms: 18000, category: "scam", submitter_hash: "B" }, existing).kind, "insert");
	const dead = [{ ...seg("x", 5000, 11000, -2), hidden: 1, submitter_hash: "A" }];
	assert.equal(decide({ start_ms: 5000, end_ms: 11000, category: "scam", submitter_hash: "C" }, dead).kind, "reject");
});

test("Уилсон: 3 из 3 весит меньше, чем 30 из 30", () => {
	assert.ok(wilson(3, 0) < wilson(30, 0));
	assert.equal(wilson(0, 0), 0);
});
