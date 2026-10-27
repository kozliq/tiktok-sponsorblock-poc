// Чистая логика без D1 и сети: проверка отметок, перекрытия, выбор отрезков для показа.
// Работает и в Worker, и в node --test.

export const CATEGORIES = ["sponsor", "scam", "selfpromo", "intro"] as const;
export type Category = (typeof CATEGORIES)[number];

export interface Segment {
	uuid: string;
	start_ms: number;
	end_ms: number;
	category: string;
	votes: number;
	locked: number;
	created_at: number;
}

export interface Submission {
	videoID: string;
	userID: string;
	startMs: number;
	endMs: number;
	durationMs: number;
	category: string;
}

export const MIN_SEGMENT_MS = 1000;        // короче секунды — не реклама, а дрожь пальца
export const MAX_VIDEO_MS = 10 * 60_000;   // TikTok даёт до 10 минут
export const MAX_SHARE = 0.5;              // отрезок больше половины ролика — тролль «0:00–0:15»
export const MERGE_IOU = 0.6;              // настолько совпадающая отметка — это голос «за», а не новый отрезок
export const HIDE_AT = -2;                 // столько чистых минусов — и отрезок никому не показывается
export const SHADOWBAN_AT = -5;            // репутация, после которой человек размечает «в пустоту»

const VIDEO_ID = /^\d{8,20}$/;
const USER_ID = /^[A-Za-z0-9_-]{16,64}$/;

/** Ошибка для человека или null, если отметка годится. */
export function validateSubmission(s: Partial<Submission>, maxSegmentMs = 60_000): string | null {
	if (typeof s.videoID !== "string" || !VIDEO_ID.test(s.videoID)) return "videoID: нужен числовой aweme_id";
	if (typeof s.userID !== "string" || !USER_ID.test(s.userID)) return "userID: 16–64 символа [A-Za-z0-9_-]";
	if (!CATEGORIES.includes(s.category as Category)) return `category: одно из ${CATEGORIES.join(", ")}`;
	for (const k of ["startMs", "endMs", "durationMs"] as const) {
		if (!Number.isSafeInteger(s[k])) return `${k}: целое число миллисекунд`;
	}
	const start = s.startMs!, end = s.endMs!, dur = s.durationMs!;
	if (dur < 3000 || dur > MAX_VIDEO_MS) return "durationMs: ролик от 3 с до 10 мин";
	if (start < 0 || end <= start) return "отрезок перевёрнут или начинается до нуля";
	if (end > dur + 500) return "отрезок выходит за конец ролика";
	const len = end - start;
	if (len < MIN_SEGMENT_MS) return "отрезок короче 1 с";
	if (len > maxSegmentMs) return `отрезок длиннее ${maxSegmentMs / 1000} с`;
	if (len > dur * MAX_SHARE) return "отрезок больше половины ролика";
	return null;
}

export function overlapMs(a: { start_ms: number; end_ms: number }, b: { start_ms: number; end_ms: number }): number {
	return Math.max(0, Math.min(a.end_ms, b.end_ms) - Math.max(a.start_ms, b.start_ms));
}

/** Intersection over Union: 1 — одинаковые отрезки, 0 — не касаются. */
export function iou(a: { start_ms: number; end_ms: number }, b: { start_ms: number; end_ms: number }): number {
	const inter = overlapMs(a, b);
	if (inter === 0) return 0;
	const union = Math.max(a.end_ms, b.end_ms) - Math.min(a.start_ms, b.start_ms);
	return inter / union;
}

/** Нижняя граница Уилсона (95%): насколько можно верить отрезку при малом числе голосов. */
export function wilson(up: number, down: number): number {
	const n = up + down;
	if (n === 0) return 0;
	const z = 1.96, p = up / n;
	const lower = (p + (z * z) / (2 * n) - z * Math.sqrt((p * (1 - p) + (z * z) / (4 * n)) / n)) / (1 + (z * z) / n);
	return Math.max(0, lower);   // при up = 0 точный ответ — ноль, а плавающая точка даёт −1e−17
}

function better(a: Segment, b: Segment): number {
	return b.locked - a.locked || b.votes - a.votes || a.created_at - b.created_at || (a.uuid < b.uuid ? -1 : a.uuid > b.uuid ? 1 : 0);
}

/**
 * Из всех видимых отметок одного ролика выбирает непересекающиеся:
 * жадно берём лучший отрезок (закреплён → больше голосов → раньше отмечен)
 * и выкидываем всё, что с ним перекрывается.
 */
export function pickSegments(all: Segment[]): Segment[] {
	const chosen: Segment[] = [];
	for (const s of [...all].sort(better)) {
		if (s.votes <= HIDE_AT && !s.locked) continue;
		if (chosen.every((c) => overlapMs(c, s) === 0)) chosen.push(s);
	}
	return chosen.sort((a, b) => a.start_ms - b.start_ms);
}

/** Склейка пересекающихся и почти касающихся (< gap) интервалов в единые прыжки. */
export function mergeIntervals(list: [number, number][], gap = 300): [number, number][] {
	const out: [number, number][] = [];
	for (const [s, e] of [...list].sort((a, b) => a[0] - b[0])) {
		const last = out[out.length - 1];
		if (last && s <= last[1] + gap) last[1] = Math.max(last[1], e);
		else out.push([s, e]);
	}
	return out;
}

/** Что делать с новой отметкой, глядя на уже существующие у этого ролика. */
export type Decision =
	| { kind: "insert" }
	| { kind: "upvote"; uuid: string }
	| { kind: "reject"; reason: string };

export function decide(
	sub: { start_ms: number; end_ms: number; category: string; submitter_hash: string },
	existing: (Segment & { hidden: number; submitter_hash: string })[],
): Decision {
	for (const e of existing) {
		if (e.submitter_hash === sub.submitter_hash && overlapMs(e, sub) > 0)
			return { kind: "reject", reason: "вы уже отметили этот кусок" };
	}
	let best: (typeof existing)[number] | null = null;
	let bestIou = 0;
	for (const e of existing) {
		const v = iou(e, sub);
		if (v >= MERGE_IOU && v > bestIou) { best = e; bestIou = v; }
	}
	if (!best) return { kind: "insert" };
	if (best.hidden) return { kind: "reject", reason: "такой отрезок уже отклонён голосами" };
	return { kind: "upvote", uuid: best.uuid };
}
