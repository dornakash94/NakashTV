-- One row per profile of an account, and one per synced item of a profile.
-- `account` is a SHA-256 of the provider login (the server never sees the login itself).
CREATE TABLE IF NOT EXISTS profiles (
  account TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL,
  updated_at INTEGER NOT NULL, synced_at INTEGER NOT NULL,
  PRIMARY KEY (account, id)
);
CREATE TABLE IF NOT EXISTS items (
  account TEXT NOT NULL, profile TEXT NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL,
  data TEXT, deleted INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL, synced_at INTEGER NOT NULL,
  PRIMARY KEY (account, profile, kind, key)
);
CREATE INDEX IF NOT EXISTS items_since ON items (account, synced_at);
CREATE INDEX IF NOT EXISTS profiles_since ON profiles (account, synced_at);
