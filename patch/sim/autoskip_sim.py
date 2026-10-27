"""Симуляция автопропуска «Мимо»: построчная копия SponsorManager.tick()/skip() на Python
против модели плеера TikTok. Проверяет алгоритм, а не сам Java-код (его — на телефоне).

    python3 patch/sim/autoskip_sim.py 1000

Модель плеера: позиция идёт со скоростью speed, в конце ролик сам начинается с нуля (так делает
лента TikTok), seekTo применяется с задержкой (пока не применился — getCurrentPlaybackTime
отдаёт старую позицию), человек иногда перематывает руками. Свойства, которые проверяем:
  1. реклама, в которую плеер вошёл сам, видна не дольше кадра + задержка перемотки;
  2. в рекламу, куда человек перемотал сам, мод не вмешивается до конца круга;
  3. нет зацикливания: прыжков за круг не больше, чем отрезков;
  4. проверок таймлайна немного (это и есть «без просадки FPS»).
"""

import random
import sys

# ---- константы — как в SponsorManager.java ----
FAR_TICK_MS = 1000
MIN_TICK_MS = 16
END_GUARD_MS = 250
MIN_JUMP_MS = 400
MAX_COVERAGE = 0.8
LOOP_GUARD = 3          # 3 прыжка подряд почти без ролика между ними — это петля
LOOP_PROGRESS_MS = 300  # «почти без ролика»


class Player:
    def __init__(self, dur, speed, seek_lag):
        self.dur, self.speed, self.seek_lag = dur, speed, seek_lag
        self.base_pos, self.base_t = 0.0, 0.0
        self.pending = None          # (время применения, позиция, колбэк «перемотка завершена»)

    def pos_at(self, t):
        if self.pending and t >= self.pending[0]:
            at, target, done = self.pending
            self.base_pos, self.base_t, self.pending = target, at, None
            if done:
                done()               # SeekCompletionListener.onCompletion
        return (self.base_pos + (t - self.base_t) * self.speed) % self.dur

    def seek(self, t, target, lag=None, on_done=None):
        self.base_pos, self.base_t = self.pos_at(t), t
        self.pending = (t + (self.seek_lag if lag is None else lag), target, on_done)


SEEK_SETTLE_MS = 1000   # сколько ждём, пока своя перемотка применится
NEAR_MS = 300           # «приземлились» — позиция в пределах этого от цели


class Mod:
    """SponsorManager: те же поля и та же логика."""

    def __init__(self, segments, player):
        self.segments = sorted(segments)          # [(start, end, uuid)]
        self.player = player
        self.done = set()
        self.last_pos, self.last_tick_at = -1, 0
        self.own_target, self.own_until = None, 0  # перемотка в полёте (своя или человека)
        self.own_landed = False                     # плеер сообщил «перемотка завершена»
        self.user_target = None                    # перемотка человека (хук TTVideoEngine.seekTo)
        self.disabled = False
        self.landing = None                       # куда приземлились прошлым прыжком
        self.streak = 0
        self.disabled_by = None
        self.ticks = 0
        self.jumps = []                           # (время, откуда, куда)
        if coverage(self.segments) > player.dur * MAX_COVERAGE:
            self.disabled, self.disabled_by = True, "coverage"

    def tick(self, now):
        """Возвращает задержку до следующей проверки или None (планировщик остановлен)."""
        if self.disabled or not self.segments:
            return None
        self.ticks += 1
        pos = int(self.player.pos_at(now))
        dur = self.player.dur
        if self.user_target is not None:
            # человек перемотал сам: куда попал — то и хочет смотреть, даже рекламу
            to, self.user_target = self.user_target, None
            if to < self.last_pos:
                self.done.clear()
            for s, e, u in self.segments:
                if s <= to < e:
                    self.done.add(u)
            # перемотку человека TikTok делает со своим слушателем — о завершении нам не скажут,
            # поэтому ждём, пока позиция окажется рядом с целью
            self.own_target, self.own_until, self.own_landed = to, now + SEEK_SETTLE_MS, None
        if self.own_target is not None:
            if self.own_landed is None:
                landed = abs(pos - self.own_target) <= NEAR_MS
            else:
                landed = self.own_landed
            if not landed and now < self.own_until:
                return MIN_TICK_MS                    # плеер ещё отдаёт старую позицию — смотрим каждый кадр
            self.own_target = None
            self.last_pos, self.last_tick_at = pos, now
        if self.last_pos >= 0:
            elapsed = now - self.last_tick_at
            backwards = pos + 1500 < self.last_pos
            natural_loop = backwards and is_natural_loop(pos, self.last_pos, elapsed, dur)
            user_jump = not natural_loop and (backwards or pos - self.last_pos > elapsed * 2 + 400)
            if natural_loop:
                self.done.clear()
            elif user_jump:
                if backwards:
                    self.done.clear()
                for s, e, u in self.segments:
                    if s <= pos < e:
                        self.done.add(u)
        self.last_pos, self.last_tick_at = pos, now

        nxt = None
        for s, e, u in self.segments:
            if u in self.done:
                continue
            if s <= pos < e:
                return self.skip(now, (s, e, u), pos, dur)
            if s > pos and nxt is None:
                nxt = s
        return next_delay(pos, nxt, self.segments, self.done, dur)

    def skip(self, now, first, pos, dur):
        target = first[1]
        self.done.add(first[2])
        for s, e, u in self.segments:
            if s <= target + 300 and e > target:
                target = e
                self.done.add(u)
        if dur > 0:
            target = min(target, dur - END_GUARD_MS)
        if target - pos < MIN_JUMP_MS:
            return FAR_TICK_MS
        if self.landing is not None:
            played = pos - self.landing if pos >= self.landing else dur - self.landing + pos
            self.streak = self.streak + 1 if played < LOOP_PROGRESS_MS else 0
            if self.streak >= LOOP_GUARD:
                self.disabled, self.disabled_by = True, "loop"
                return None
        self.landing = target
        self.own_landed = False
        self.player.seek(now, target, on_done=self.on_seek_complete)
        self.jumps.append((now, pos, target))
        self.own_target, self.own_until = target, now + SEEK_SETTLE_MS
        self.last_pos, self.last_tick_at = target, now
        return MIN_TICK_MS

    def on_user_seek(self, ms):
        self.user_target = ms

    def on_seek_complete(self):
        self.own_landed = True


def is_natural_loop(pos, last_pos, elapsed, dur):
    # за прошедшее время (до ×3 скорости) плеер мог доиграть хвост и уйти на новый круг
    run = elapsed * 3 + 500
    return dur > 0 and last_pos > dur - run and pos < run


def next_delay(pos, nxt, segments, done, dur):
    """Половина расстояния до ближайшего отрезка. Если впереди на этом круге ничего нет,
    а отрезки есть в начале ролика — целимся в них через конец (после повтора)."""
    if nxt is not None:
        gap = nxt - pos
    else:
        ahead = [s for s, e, u in segments]       # на следующем круге «пройденные» снова в деле
        gap = dur - pos + min(ahead) if ahead and dur > 0 else None
    if gap is None:
        return FAR_TICK_MS
    return max(MIN_TICK_MS, min(FAR_TICK_MS, gap // 2))


def coverage(segments):
    total, reach = 0, -1
    for s, e, _ in segments:
        frm = max(s, reach)
        if e > frm:
            total += e - frm
        reach = max(reach, e)
    return total


# ---- сценарии ----

def scenario(n):
    rng = random.Random(n)
    dur = rng.randint(8000, 60000)
    segs = []
    for i in range(rng.choice([1, 1, 2, 3])):
        length = rng.randint(1000, min(15000, dur // 2))
        r = rng.random()
        start = 0 if r < 0.15 else (dur - length if r < 0.3 else rng.randint(0, dur - length))
        cand = (start, start + length, f"s{i}")
        # сервер (pickSegments) никогда не отдаёт пересекающиеся отрезки — и мы не генерируем
        if all(cand[1] <= a or cand[0] >= b for a, b, _ in segs):
            segs.append(cand)
    speed = rng.choice([1.0, 1.0, 1.0, 1.5, 2.0])
    lag = rng.choice([0, 0, 30, 80, 150, 300])
    user_seeks = []
    if rng.random() < 0.35:
        for _ in range(rng.randint(1, 2)):
            t = rng.randint(500, int(2.5 * dur / speed))
            if rng.random() < 0.6:                       # чаще всего — прямо в рекламу
                s, e, _ = rng.choice(segs)
                to = rng.randint(s, max(s, e - 600))
            else:
                to = rng.randint(0, dur - 1)
            user_seeks.append((t, to))
    return dur, segs, speed, lag, sorted(user_seeks)


STEP = 2   # мс модельного времени


def run(n):
    dur, segs, speed, lag, user_seeks = scenario(n)
    player = Player(dur, speed, lag)
    mod = Mod(segs, player)
    total_t = int(3 * dur / speed)
    next_tick = 0
    problems = []
    exposure = {}          # uuid → мс рекламы подряд при обычном просмотре
    user_in = set()        # отрезки, куда человек перемотал сам на этом круге
    loop_jumps, loops = 0, 0
    prev_pos = 0
    seek_queue = list(user_seeks)
    for t in range(0, total_t, STEP):
        just_seeked = False
        if seek_queue and t >= seek_queue[0][0]:
            just_seeked = True
            _, to = seek_queue.pop(0)
            player.seek(t, to, lag=0)
            mod.on_user_seek(to)
            next_tick = t                                 # хук будит планировщик сразу
            user_in = {u for s, e, u in segs if s <= to < e}
        if next_tick is not None and t >= next_tick:
            d = mod.tick(t)
            next_tick = None if d is None else t + d
        pos = player.pos_at(t)
        if pos + 1000 < prev_pos and prev_pos > dur - 1000 and pos < 1000 and not just_seeked:
            loops += 1
            if loop_jumps > len(segs) + 1:
                problems.append(f"зацикливание: {loop_jumps} прыжков за круг")
            loop_jumps = 0
            user_in = set()
            exposure.clear()
        prev_pos = pos
        for s, e, u in segs:
            if s <= pos < e and u not in user_in and not mod.disabled:
                exposure[u] = exposure.get(u, 0) + STEP * speed
            elif exposure.get(u, 0) > 0:
                exposure[u] = 0                         # вышли из рекламы — следующий заход считаем заново
    # проверяем по прыжкам: сколько рекламы было видно до прыжка при обычном входе
        for s, e, u in segs:
            if u in user_in or mod.disabled:
                continue
            seen = exposure.get(u, 0)
            # заметить вход — до 2 проверок по MIN_TICK, пока перемотка применяется — lag, плюс шаг модели
            allowed = MIN_TICK_MS * 2 * speed + lag * speed + 4 * STEP * speed + 2
            if e >= dur - END_GUARD_MS - MIN_JUMP_MS:     # реклама до конца — хвост виден по замыслу
                allowed += END_GUARD_MS + MIN_JUMP_MS
            if e - s < MIN_JUMP_MS + 50:
                allowed = e - s
            if seen > allowed + 1:
                problems.append(f"реклама {u} [{s}–{e}] видна {seen:.0f} мс при допуске {allowed:.0f}")
                exposure[u] = -10 ** 9          # одна запись на отрезок
    # перемотал руками в рекламу — мод не должен выдёргивать оттуда в том же круге
    for i, (ut, uto) in enumerate(user_seeks):
        inside = [(s, e) for s, e, u in segs if s <= uto < e]
        if not inside:
            continue
        s, e = inside[0]
        until = ut + (e - uto) / speed - 50
        if i + 1 < len(user_seeks):
            until = min(until, user_seeks[i + 1][0])     # человек перемотал ещё раз — его выбор сменился
        for jt, frm, to in mod.jumps:
            if ut < jt < until and s <= frm < e:
                problems.append(f"выдернул из рекламы, куда человек перемотал сам ({uto} мс)")
                break
    ticks_per_sec = mod.ticks / (total_t / 1000)
    if mod.disabled_by == "loop":
        problems.append("защита от петли выключила пропуск — в нормальном сценарии так быть не должно")
    return problems, ticks_per_sec, mod.disabled_by, dict(dur=dur, segs=segs, speed=speed, lag=lag, seeks=user_seeks)


def main(total):
    bad, tps, disabled = 0, [], {}
    for n in range(total):
        problems, rate, dis, meta = run(n)
        tps.append(rate)
        if dis:
            disabled[dis] = disabled.get(dis, 0) + 1
        if problems:
            bad += 1
            if bad <= 15:
                print(f"✘ #{n}: {problems[0]}  {meta}")
    tps.sort()
    print(f"\nСценариев: {total}, без ошибок: {total - bad}, с ошибками: {bad}")
    print(f"Проверок таймлайна в секунду: медиана {tps[len(tps) // 2]:.1f}, максимум {tps[-1]:.1f} "
          f"(опрос каждый кадр был бы 60)")
    print(f"Пропуск выключался защитой: {disabled or 'ни разу'} "
          f"(coverage — отрезки на 80%+ ролика, это задумано)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(int(sys.argv[1]) if len(sys.argv) > 1 else 1000))
