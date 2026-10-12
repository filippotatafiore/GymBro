"""
GymBro REST API.

A small Flask server that stores the users of the app and the posts they publish
(results of finished gym/run sessions or results added manually) in a SQL (SQLite) database.

Run it locally (Android emulator reaches it at http://10.0.2.2:5000):
    python app.py

On PythonAnywhere the WSGI file imports `app` from this module (see README.md).
"""

import hashlib
import json
import os
import re
import secrets
import sqlite3
from datetime import datetime, timezone
from functools import wraps

from flask import Flask, g, jsonify, request, send_file
from werkzeug.security import check_password_hash, generate_password_hash

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("GYMBRO_DB", os.path.join(BASE_DIR, "gymbro.db"))
UPLOAD_DIR = os.path.join(BASE_DIR, "uploads")

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 5 * 1024 * 1024  # biggest accepted request (profile pictures)


# ------------------------------------------------------------------ DATABASE

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    email         TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT    NOT NULL,
    has_picture   INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL
);

-- Login tokens: only their hash is stored, so a leaked database does not leak valid tokens
CREATE TABLE IF NOT EXISTS tokens (
    token_hash TEXT    PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TEXT    NOT NULL
);

-- Posts published by the users on their profile. Three kinds:
--   manual: a result added by hand, e.g. Deadlift - weight - 150 kg - higher is better
--   gym:    a finished gym session (training time + exercises)
--   run:    a finished run session (training time + distance)
CREATE TABLE IF NOT EXISTS posts (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind          TEXT    NOT NULL CHECK (kind IN ('manual', 'gym', 'run')),
    title         TEXT    NOT NULL,  -- exercise/activity (manual), 'Gym session' or 'Run'
    metric        TEXT,              -- manual: 'time' or 'weight'
    better        TEXT,              -- manual: 'higher' or 'lower' (which result is better)
    amount        REAL,              -- manual: seconds (time) or kg (weight)
    notes         TEXT,              -- manual, optional
    duration_s    INTEGER,           -- gym / run: training time in seconds
    distance_km   REAL,              -- run
    exercises     TEXT,              -- gym: JSON list of the exercises
    session_start TEXT,              -- gym / run: when the session was done
    created_at    TEXT    NOT NULL
);

-- Old format of the published results, replaced by "posts"
DROP TABLE IF EXISTS results;
"""


def init_db():
    os.makedirs(UPLOAD_DIR, exist_ok=True)
    with sqlite3.connect(DB_PATH) as db:
        db.executescript(SCHEMA)


def get_db():
    """One database connection per request."""
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys = ON")
    return g.db


@app.teardown_appcontext
def close_db(_exception):
    db = g.pop("db", None)
    if db is not None:
        db.close()


# ------------------------------------------------------------------ HELPERS

USERNAME_PATTERN = re.compile(r"^[A-Za-z0-9_.]{3,30}$")
EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def error(status, message):
    return jsonify({"error": message}), status


def hash_token(token):
    return hashlib.sha256(token.encode()).hexdigest()


def create_token(user_id):
    token = secrets.token_urlsafe(32)
    db = get_db()
    db.execute(
        "INSERT INTO tokens (token_hash, user_id, created_at) VALUES (?, ?, ?)",
        (hash_token(token), user_id, now_iso()),
    )
    db.commit()
    return token


def user_json(user, include_email=False):
    data = {
        "id": user["id"],
        "username": user["username"],
        "has_picture": bool(user["has_picture"]),
        "created_at": user["created_at"],
    }
    if include_email:
        data["email"] = user["email"]
    return data


def post_json(post):
    return {
        "id": post["id"],
        "user_id": post["user_id"],
        "kind": post["kind"],
        "title": post["title"],
        "metric": post["metric"],
        "better": post["better"],
        "amount": post["amount"],
        "notes": post["notes"],
        "duration_s": post["duration_s"],
        "distance_km": post["distance_km"],
        "exercises": json.loads(post["exercises"]) if post["exercises"] else None,
        "session_start": post["session_start"],
        "created_at": post["created_at"],
    }


def number(data, key, minimum, maximum):
    """The number data[key] if it is between minimum and maximum, otherwise None."""
    value = data.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return value if minimum <= value <= maximum else None


def session_start(data):
    """The session_start of the request (e.g. 2026-10-12T15:30:00Z), or None if invalid."""
    value = str(data.get("session_start", ""))
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return None
    return value


def clean_exercises(exercises):
    """The exercises of a gym session, validated, or None if invalid."""
    if not isinstance(exercises, list) or not 1 <= len(exercises) <= 50:
        return None
    cleaned = []
    for exercise in exercises:
        if not isinstance(exercise, dict):
            return None
        name = str(exercise.get("name", "")).strip()
        sets = number(exercise, "sets", 1, 100)
        reps = number(exercise, "reps", 1, 1000)
        weight = exercise.get("weight_kg")
        if not 1 <= len(name) <= 60 or sets is None or reps is None:
            return None
        if weight is not None and number(exercise, "weight_kg", 0, 1000) is None:
            return None
        cleaned.append({
            "name": name,
            "muscle_group": str(exercise.get("muscle_group", "")).strip()[:60],
            "sets": int(sets),
            "reps": int(reps),
            "weight_kg": weight,
        })
    return cleaned


def picture_path(user_id):
    return os.path.join(UPLOAD_DIR, f"{user_id}.jpg")


def login_required(view):
    """Lets the request through only with a valid 'Authorization: Bearer <token>' header."""

    @wraps(view)
    def wrapper(*args, **kwargs):
        header = request.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return error(401, "Login required")
        token_hash = hash_token(header[len("Bearer "):])
        user = get_db().execute(
            "SELECT users.* FROM tokens JOIN users ON users.id = tokens.user_id "
            "WHERE tokens.token_hash = ?",
            (token_hash,),
        ).fetchone()
        if user is None:
            return error(401, "Your session expired, please log in again")
        g.user = user
        g.token_hash = token_hash
        return view(*args, **kwargs)

    return wrapper


@app.errorhandler(404)
def not_found(_e):
    return error(404, "Not found")


@app.errorhandler(413)
def too_large(_e):
    return error(413, "The file is too big (max 5 MB)")


# ------------------------------------------------------------------ ENDPOINTS

@app.get("/api/health")
def health():
    return jsonify({"status": "ok"})


@app.post("/api/register")
def register():
    data = request.get_json(silent=True) or {}
    username = str(data.get("username", "")).strip()
    email = str(data.get("email", "")).strip()
    password = str(data.get("password", ""))

    if not USERNAME_PATTERN.match(username):
        return error(400, "The username must be 3-30 characters: letters, numbers, '_' or '.'")
    if not EMAIL_PATTERN.match(email):
        return error(400, "Invalid email address")
    if len(password) < 6:
        return error(400, "The password must be at least 6 characters")

    db = get_db()
    try:
        cursor = db.execute(
            "INSERT INTO users (username, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
            (username, email, generate_password_hash(password, method="pbkdf2:sha256"), now_iso()),
        )
        db.commit()
    except sqlite3.IntegrityError:
        return error(409, "Username or email already registered")

    user = db.execute("SELECT * FROM users WHERE id = ?", (cursor.lastrowid,)).fetchone()
    return jsonify({"token": create_token(user["id"]), "user": user_json(user, include_email=True)}), 201


@app.post("/api/login")
def login():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email", "")).strip()
    password = str(data.get("password", ""))

    user = get_db().execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
    if user is None or not check_password_hash(user["password_hash"], password):
        return error(401, "Wrong email or password")

    return jsonify({"token": create_token(user["id"]), "user": user_json(user, include_email=True)})


@app.post("/api/logout")
@login_required
def logout():
    db = get_db()
    db.execute("DELETE FROM tokens WHERE token_hash = ?", (g.token_hash,))
    db.commit()
    return "", 204


@app.get("/api/me")
@login_required
def me():
    return jsonify(user_json(g.user, include_email=True))


@app.put("/api/me/picture")
@login_required
def upload_picture():
    image = request.get_data()
    is_jpeg = image.startswith(b"\xff\xd8\xff")
    if not is_jpeg:
        return error(400, "The picture must be a JPEG image")

    with open(picture_path(g.user["id"]), "wb") as file:
        file.write(image)
    db = get_db()
    db.execute("UPDATE users SET has_picture = 1 WHERE id = ?", (g.user["id"],))
    db.commit()
    return "", 204


@app.get("/api/users/<int:user_id>")
def public_profile(user_id):
    user = get_db().execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if user is None:
        return error(404, "User not found")
    return jsonify(user_json(user))


@app.get("/api/users/<int:user_id>/picture")
def get_picture(user_id):
    path = picture_path(user_id)
    if not os.path.exists(path):
        return error(404, "No profile picture")
    return send_file(path, mimetype="image/jpeg", max_age=0)


@app.get("/api/users/<int:user_id>/posts")
def user_posts(user_id):
    rows = get_db().execute(
        "SELECT * FROM posts WHERE user_id = ? ORDER BY created_at DESC, id DESC", (user_id,)
    ).fetchall()
    return jsonify([post_json(row) for row in rows])


@app.post("/api/me/posts")
@login_required
def publish_post():
    data = request.get_json(silent=True) or {}
    kind = data.get("kind")
    post = {"kind": kind}

    if kind == "manual":
        post["title"] = str(data.get("title", "")).strip()
        post["metric"] = data.get("metric")
        post["better"] = data.get("better")
        post["amount"] = number(data, "amount", 0.001, 1_000_000)
        post["notes"] = str(data.get("notes") or "").strip()
        if not 1 <= len(post["title"]) <= 60:
            return error(400, "The exercise/activity must be 1-60 characters")
        if post["metric"] not in ("time", "weight") or post["better"] not in ("higher", "lower"):
            return error(400, "Choose how the result is measured and which result is better")
        if post["amount"] is None:
            return error(400, "Invalid result")
        if len(post["notes"]) > 200:
            return error(400, "The notes must be at most 200 characters")

    elif kind in ("gym", "run"):
        post["title"] = "Gym session" if kind == "gym" else "Run"
        post["duration_s"] = number(data, "duration_s", 1, 7 * 24 * 3600)
        post["session_start"] = session_start(data)
        if post["duration_s"] is None or post["session_start"] is None:
            return error(400, "Invalid session")
        post["duration_s"] = int(post["duration_s"])

        if kind == "run":
            post["distance_km"] = number(data, "distance_km", 0.001, 1000)
            if post["distance_km"] is None:
                return error(400, "Invalid distance")
        else:
            exercises = clean_exercises(data.get("exercises"))
            if exercises is None:
                return error(400, "Invalid exercises")
            post["exercises"] = json.dumps(exercises)

    else:
        return error(400, "The kind of post must be 'manual', 'gym' or 'run'")

    post["user_id"] = g.user["id"]
    post["created_at"] = now_iso()
    columns = ", ".join(post)
    placeholders = ", ".join("?" for _ in post)

    db = get_db()
    cursor = db.execute(f"INSERT INTO posts ({columns}) VALUES ({placeholders})", tuple(post.values()))
    db.commit()
    row = db.execute("SELECT * FROM posts WHERE id = ?", (cursor.lastrowid,)).fetchone()
    return jsonify(post_json(row)), 201


@app.delete("/api/me/posts/<int:post_id>")
@login_required
def delete_post(post_id):
    db = get_db()
    cursor = db.execute("DELETE FROM posts WHERE id = ? AND user_id = ?", (post_id, g.user["id"]))
    db.commit()
    if cursor.rowcount == 0:
        return error(404, "Post not found")
    return "", 204


# Create the tables when the module is loaded (also on PythonAnywhere)
init_db()

if __name__ == "__main__":
    # 127.0.0.1: reachable only from this PC (and from the Android emulator, as 10.0.2.2)
    app.run(host="127.0.0.1", port=5000, debug=True)
