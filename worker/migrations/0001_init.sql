-- Мимо: схема D1. Читается в тысячи раз чаще, чем пишется, поэтому
-- горячий путь (GET по префиксу хэша) идёт по частичному индексу только видимых отрезков.

CREATE TABLE segments (
  uuid           TEXT    PRIMARY KEY,
  video_id       TEXT    NOT NULL,             -- aweme_id ролика (19 цифр)
  video_hash     TEXT    NOT NULL,             -- sha256(video_id), hex
  prefix4        TEXT    NOT NULL,             -- первые 4 hex-символа хэша: ключ k-анонимного чтения
  start_ms       INTEGER NOT NULL,
  end_ms         INTEGER NOT NULL,
  duration_ms    INTEGER NOT NULL,             -- длина ролика, как её видел автор отметки
  category       TEXT    NOT NULL,             -- sponsor | scam | selfpromo | intro
  votes          INTEGER NOT NULL DEFAULT 0,   -- upvotes - downvotes
  upvotes        INTEGER NOT NULL DEFAULT 0,
  downvotes      INTEGER NOT NULL DEFAULT 0,
  submitter_hash TEXT    NOT NULL,             -- HMAC(PEPPER, userID): сам userID не хранится
  hidden         INTEGER NOT NULL DEFAULT 0,   -- 1 = скрыт голосами или теневым баном
  locked         INTEGER NOT NULL DEFAULT 0,   -- 1 = подтверждён вручную, голоса его не скрывают
  created_at     INTEGER NOT NULL,             -- unix ms
  CHECK (start_ms >= 0 AND end_ms > start_ms),
  CHECK (category IN ('sponsor', 'scam', 'selfpromo', 'intro'))
);

-- Горячий путь: WHERE prefix4 = ? AND hidden = 0
CREATE INDEX idx_segments_visible ON segments (prefix4) WHERE hidden = 0;
-- Запись: проверка дублей и перекрытий по одному ролику
CREATE INDEX idx_segments_video ON segments (video_id);
-- Лимит отметок на человека в сутки и теневой бан
CREATE INDEX idx_segments_submitter ON segments (submitter_hash, created_at);

CREATE TABLE votes (
  segment_uuid TEXT    NOT NULL,
  voter_hash   TEXT    NOT NULL,
  vote         INTEGER NOT NULL CHECK (vote IN (-1, 1)),
  created_at   INTEGER NOT NULL,
  PRIMARY KEY (segment_uuid, voter_hash)
) WITHOUT ROWID;

CREATE TABLE users (
  user_hash    TEXT    PRIMARY KEY,
  reputation   INTEGER NOT NULL DEFAULT 0,     -- сумма голосов за все его отрезки
  shadowbanned INTEGER NOT NULL DEFAULT 0,     -- 1 = отметки принимаются, но никому не показываются
  created_at   INTEGER NOT NULL
) WITHOUT ROWID;
