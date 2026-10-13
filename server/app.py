"""
GymBro REST API.

A small Flask server that stores the users of the app (with their body data) and the posts
they publish (results of finished gym/run sessions or results added manually) in a SQL (SQLite)
database. It also computes the walking routes of the runs with OpenRouteService (or the Google
Routes API), so that the keys of these services stay on the server.

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
import urllib.error
import urllib.request
from datetime import datetime, timezone
from functools import wraps

from flask import Flask, g, jsonify, request, send_file
from werkzeug.security import check_password_hash, generate_password_hash

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("GYMBRO_DB", os.path.join(BASE_DIR, "gymbro.db"))
UPLOAD_DIR = os.path.join(BASE_DIR, "uploads")
# Walking routes of the runs (see README.md): keys in environment variables or text files
ORS_KEY_FILE = os.path.join(BASE_DIR, "ors_api_key.txt")                # OpenRouteService (free)
ORS_URL = "https://api.openrouteservice.org/v2/directions/foot-walking"
GOOGLE_ROUTES_KEY_FILE = os.path.join(BASE_DIR, "routes_api_key.txt")   # Google (needs billing)
GOOGLE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
# Public OSRM server of FOSSGIS (OpenStreetMap data, no key): used if the services above fail
OSRM_FOOT_URL = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/"
# Maximum wait for each service: the app waits 30 s for the whole answer of the server
SERVICE_TIMEOUT_S = 8

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
    created_at    TEXT    NOT NULL,
    -- body data (optional): calories of the runs, BMI
    height_cm     REAL,
    weight_kg     REAL,
    birth_year    INTEGER,
    sex           TEXT              -- 'male', 'female' or 'other'
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
--   run:    a finished run session (training time, distance, statistics and route followed)
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
    distance_km   REAL,              -- run: rounded to 0.1 km
    kcal          REAL,              -- run: calories burned (estimated)
    max_speed_kmh REAL,              -- run
    goal_km       REAL,              -- run: goal distance (NULL = no goal)
    route         TEXT,              -- run: JSON list of lines (Google encoded polylines)
    exercises     TEXT,              -- gym: JSON list of the exercises
    session_start TEXT,              -- gym / run: when the session was done
    created_at    TEXT    NOT NULL
);

-- Old format of the published results, replaced by "posts"
DROP TABLE IF EXISTS results;
"""


# Columns added to the tables after their first version: added to the existing databases too
# (the users and posts already there are kept, with these columns empty)
NEW_COLUMNS = {
    "users": {
        "height_cm": "REAL",
        "weight_kg": "REAL",
        "birth_year": "INTEGER",
        "sex": "TEXT",
    },
    "posts": {
        "kcal": "REAL",
        "max_speed_kmh": "REAL",
        "goal_km": "REAL",
        "route": "TEXT",
    },
}


def init_db():
    os.makedirs(UPLOAD_DIR, exist_ok=True)
    with sqlite3.connect(DB_PATH) as db:
        db.executescript(SCHEMA)
        for table, columns in NEW_COLUMNS.items():
            existing = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
            for column, sql_type in columns.items():
                if column not in existing:
                    db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {sql_type}")


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


def user_json(user, private=False):
    """private=True: also email and body data (only for the user themself)."""
    data = {
        "id": user["id"],
        "username": user["username"],
        "has_picture": bool(user["has_picture"]),
        "created_at": user["created_at"],
    }
    if private:
        data["email"] = user["email"]
        data["height_cm"] = user["height_cm"]
        data["weight_kg"] = user["weight_kg"]
        data["birth_year"] = user["birth_year"]
        data["sex"] = user["sex"]
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
        "kcal": post["kcal"],
        "max_speed_kmh": post["max_speed_kmh"],
        "goal_km": post["goal_km"],
        "route": json.loads(post["route"]) if post["route"] else None,
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


def clean_route(route):
    """The route of a run (list of encoded polylines), validated, or None if invalid."""
    if not isinstance(route, list) or len(route) > 100:
        return None
    total = 0
    for line in route:
        # encoded polylines use only the characters from '?' (63) to '~' (126)
        if not isinstance(line, str) or any(not 63 <= ord(c) <= 126 for c in line):
            return None
        total += len(line)
    return route if total <= 500_000 else None


def optional_number(data, key, minimum, maximum):
    """(ok, value): value is None if the key is missing; ok is False if the value is invalid."""
    if data.get(key) is None:
        return True, None
    value = number(data, key, minimum, maximum)
    return value is not None, value


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
    return jsonify({"token": create_token(user["id"]), "user": user_json(user, private=True)}), 201


@app.post("/api/login")
def login():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email", "")).strip()
    password = str(data.get("password", ""))

    user = get_db().execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
    if user is None or not check_password_hash(user["password_hash"], password):
        return error(401, "Wrong email or password")

    return jsonify({"token": create_token(user["id"]), "user": user_json(user, private=True)})


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
    return jsonify(user_json(g.user, private=True))


@app.put("/api/me/profile")
@login_required
def update_profile():
    """Body data of the user. Every value is optional: missing or null = not set."""
    data = request.get_json(silent=True) or {}
    this_year = datetime.now(timezone.utc).year
    profile = {}
    for key, minimum, maximum in (("height_cm", 50, 260), ("weight_kg", 20, 400), ("birth_year", 1900, this_year)):
        ok, profile[key] = optional_number(data, key, minimum, maximum)
        if not ok:
            return error(400, f"Invalid {key}")
    if profile["birth_year"] is not None:
        profile["birth_year"] = int(profile["birth_year"])
    profile["sex"] = data.get("sex")
    if profile["sex"] not in (None, "male", "female", "other"):
        return error(400, "Invalid sex")

    db = get_db()
    db.execute(
        "UPDATE users SET height_cm = ?, weight_kg = ?, birth_year = ?, sex = ? WHERE id = ?",
        (profile["height_cm"], profile["weight_kg"], profile["birth_year"], profile["sex"], g.user["id"]),
    )
    db.commit()
    user = db.execute("SELECT * FROM users WHERE id = ?", (g.user["id"],)).fetchone()
    return jsonify(user_json(user, private=True))


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
            post["distance_km"] = number(data, "distance_km", 0, 1000)
            if post["distance_km"] is None:
                return error(400, "Invalid distance")
            # optional statistics (missing in the runs recorded before they existed)
            for key, minimum, maximum in (("kcal", 0, 100_000), ("max_speed_kmh", 0, 200), ("goal_km", 0.01, 1000)):
                ok, post[key] = optional_number(data, key, minimum, maximum)
                if not ok:
                    return error(400, f"Invalid {key}")
            if data.get("route") is not None:
                route = clean_route(data["route"])
                if route is None:
                    return error(400, "Invalid route")
                post["route"] = json.dumps(route)
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


def api_key(variable, file_path):
    """A key of an external service: environment variable or text file (None if missing)."""
    key = os.environ.get(variable, "").strip()
    if not key and os.path.exists(file_path):
        # utf-8-sig: also files saved with a BOM (e.g. by some Windows editors)
        with open(file_path, encoding="utf-8-sig") as file:
            key = file.read().strip()
    return key or None


def route_point(data, key):
    """A point of the request ({"lat": ..., "lng": ...}) as (lat, lng), or None."""
    point = data.get(key)
    if not isinstance(point, dict):
        return None
    lat = number(point, "lat", -90, 90)
    lng = number(point, "lng", -180, 180)
    if lat is None or lng is None:
        return None
    return lat, lng


class RouteError(Exception):
    """A walking route service could not give the route (the message says why)."""


def call_service(url, service, body=None, headers=None):
    """Request to an external service (POST of a JSON body, or GET if there is no body).
    Returns its JSON answer, or raises RouteError."""
    service_request = urllib.request.Request(
        url,
        data=json.dumps(body).encode() if body is not None else None,
        method="POST" if body is not None else "GET",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            # the public services ask to say who is calling
            "User-Agent": "GymBro/1.0 (university project)",
            **(headers or {}),
        },
    )
    try:
        with urllib.request.urlopen(service_request, timeout=SERVICE_TIMEOUT_S) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        raise RouteError(f"{service}: {error_message(e)}")
    except (urllib.error.URLError, TimeoutError, OSError):
        raise RouteError(f"{service} not reachable (no answer in {SERVICE_TIMEOUT_S} s)")


def error_message(http_error):
    """The message of an error answer: {"error": "..."} or {"error": {"message": "..."}}."""
    try:
        message = json.loads(http_error.read().decode()).get("error")
    except (ValueError, AttributeError):
        message = None
    if isinstance(message, dict):
        message = message.get("message")
    return str(message) if message else f"error {http_error.code}"


def route_openrouteservice(key, points):
    """Walking route with OpenRouteService (free, OpenStreetMap data): (meters, encoded polyline).
    https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/"""
    # OpenRouteService wants the points as [longitude, latitude]
    body = {"coordinates": [[lng, lat] for lat, lng in points]}
    answer = call_service(ORS_URL, "OpenRouteService", body, {"Authorization": key})
    routes = answer.get("routes") or []
    if not routes:
        raise RouteError("OpenRouteService: no walking route found")
    # geometry: Google encoded polyline (the same format used by the app)
    return routes[0].get("summary", {}).get("distance", 0), routes[0].get("geometry", "")


def route_google(key, points):
    """Walking route with the Google Routes API (needs billing enabled): (meters, encoded polyline)."""
    def waypoint(point):
        return {"location": {"latLng": {"latitude": point[0], "longitude": point[1]}}}

    body = {"origin": waypoint(points[0]), "destination": waypoint(points[-1]), "travelMode": "WALK"}
    if len(points) > 2:
        body["intermediates"] = [waypoint(p) for p in points[1:-1]]
    headers = {
        "X-Goog-Api-Key": key,
        # only the needed fields of the answer
        "X-Goog-FieldMask": "routes.distanceMeters,routes.polyline.encodedPolyline",
    }
    try:
        answer = call_service(GOOGLE_ROUTES_URL, "Google Routes API", body, headers)
    except RouteError as e:
        # the Routes API answers only "The caller does not have permission" also when the cause
        # is the billing of the project
        if "permission" in str(e):
            raise RouteError(f"{e} (check in Google Cloud that billing is enabled for the project of the key)")
        raise
    routes = answer.get("routes") or []
    if not routes:
        raise RouteError("Google Routes API: no walking route found")
    return routes[0].get("distanceMeters", 0), routes[0].get("polyline", {}).get("encodedPolyline", "")


def route_osrm(_key, points):
    """Walking route with the public OSRM server of FOSSGIS (free, no key, OpenStreetMap data):
    (meters, encoded polyline). Fair use: at most 1 request per second, no heavy usage.
    https://routing.openstreetmap.de/about.html"""
    # OSRM wants the points as "longitude,latitude;longitude,latitude"
    coordinates = ";".join(f"{lng},{lat}" for lat, lng in points)
    answer = call_service(f"{OSRM_FOOT_URL}{coordinates}?overview=full&geometries=polyline", "OSRM (FOSSGIS)")
    routes = answer.get("routes") or []
    if answer.get("code") != "Ok" or not routes:
        raise RouteError(f"OSRM (FOSSGIS): {answer.get('message', 'no walking route found')}")
    # geometry: Google encoded polyline (the same format used by the app)
    return routes[0].get("distance", 0), routes[0].get("geometry", "")


# Walking route services, in order of preference: (name shown in the app, key, function, attribution).
# The services with a key are used only if their key is set up; OSRM (FOSSGIS) needs no key.
def route_services():
    services = []
    ors_key = api_key("ORS_API_KEY", ORS_KEY_FILE)
    if ors_key:
        services.append(("OpenRouteService", ors_key, route_openrouteservice,
                         "© openrouteservice.org by HeiGIT · Map data © OpenStreetMap contributors"))
    services.append(("OpenStreetMap (FOSSGIS)", None, route_osrm,
                     "Routing © FOSSGIS (routing.openstreetmap.de) · Map data © OpenStreetMap contributors"))
    google_key = api_key("ROUTES_API_KEY", GOOGLE_ROUTES_KEY_FILE)
    if google_key:
        services.append(("Google Maps", google_key, route_google, None))
    return services


@app.post("/api/routes/walking")
@login_required
def walking_route():
    """Walking route origin -> stop (optional) -> destination, from the first service that answers:
    OpenRouteService, OSRM of FOSSGIS, Google Routes API. The keys of the services stay on the server.
    Body: {"origin": {"lat": .., "lng": ..}, "stop": {...} or null, "destination": {...}}"""
    data = request.get_json(silent=True) or {}
    origin = route_point(data, "origin")
    destination = route_point(data, "destination")
    stop = route_point(data, "stop")
    if origin is None or destination is None or (data.get("stop") is not None and stop is None):
        return error(400, "Invalid points")
    points = [origin] + ([stop] if stop is not None else []) + [destination]

    problems = []
    for name, key, compute, attribution in route_services():
        try:
            meters, polyline = compute(key, points)
        except RouteError as e:
            problems.append(str(e))
            continue
        return jsonify({"distance_m": meters, "polyline": polyline, "provider": name, "attribution": attribution})
    return error(502, "; ".join(problems))


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
