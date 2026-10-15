"""
GymBro REST API.

A small Flask server that stores the users of the app (with their body data) and the posts
they publish (results of finished gym/run sessions or results added manually) in Cloud Firestore,
the database of Firebase. It also computes the walking routes of the runs with OpenRouteService
(or the Google Routes API) and answers the chat with the AI coach with a language model of Groq
(or Google Gemini), so that the keys of these services stay on the server.

It runs online on Vercel, always reachable (see README.md). To run it on this PC instead
(the Android emulator reaches it at http://10.0.2.2:5000):
    python app.py
"""

import hashlib
import json
import os
import re
import secrets
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from functools import wraps

from flask import Flask, Response, g, jsonify, request
from google.api_core.exceptions import GoogleAPIError
from google.auth.exceptions import GoogleAuthError
from google.cloud import firestore
from werkzeug.security import check_password_hash, generate_password_hash

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
# Key of the Firebase service account, which lets the server use the database (see README.md):
# environment variable FIREBASE_SERVICE_ACCOUNT (on Vercel) or this file (on this PC)
FIREBASE_KEY_FILE = os.path.join(BASE_DIR, "firebase_key.json")
# Walking routes of the runs (see README.md): keys in environment variables or text files
ORS_KEY_FILE = os.path.join(BASE_DIR, "ors_api_key.txt")                # OpenRouteService (free)
ORS_URL = "https://api.openrouteservice.org/v2/directions/foot-walking"
GOOGLE_ROUTES_KEY_FILE = os.path.join(BASE_DIR, "routes_api_key.txt")   # Google (needs billing)
GOOGLE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
# Public OSRM server of FOSSGIS (OpenStreetMap data, no key): used if the services above fail
OSRM_FOOT_URL = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/"
# Maximum wait for each service: the app waits 30 s for the whole answer of the server
SERVICE_TIMEOUT_S = 8
# Chat with the AI coach (see README.md): OpenAI-compatible chat APIs, keys in environment variables
# or text files. Models tried in this order until one answers: each model has its own free limits.
# The environment variables GROQ_MODELS / GEMINI_MODELS (names separated by commas) replace them.
GROQ_KEY_FILE = os.path.join(BASE_DIR, "groq_api_key.txt")              # Groq (free, no card)
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODELS = "openai/gpt-oss-120b,qwen/qwen3.8-27b,openai/gpt-oss-20b"
GEMINI_KEY_FILE = os.path.join(BASE_DIR, "gemini_api_key.txt")          # Google Gemini (optional, free)
GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
GEMINI_MODELS = "gemini-3.8-flash,gemini-3.5-flash-lite"
# The app waits 60 s for the answer: no new model is tried after CHAT_DEADLINE_S
CHAT_TIMEOUT_S = 25
CHAT_DEADLINE_S = 45
# Only the last messages of the conversation are sent to the model (about 3000 tokens):
# a longer request would exceed the free tokens per minute of the models
CHAT_HISTORY_CHARS = 10_000
MAX_CHAT_MESSAGE_CHARS = 12_000   # an answer of MAX_ANSWER_TOKENS tokens fits
# Longest answer (reasoning included, for the models that reason before answering)
MAX_ANSWER_TOKENS = 1_500
# Biggest profile picture: a Firestore document holds at most 1 MiB (the app sends ~50-150 KB)
MAX_PICTURE_BYTES = 900_000

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 4 * 1024 * 1024  # biggest accepted request (Vercel: at most 4.5 MB)


# ------------------------------------------------------------------ DATABASE (Cloud Firestore)
#
# Firestore stores "documents" (similar to the rows of a SQL table) in "collections":
#
#   users/{id}       a user: id, username, email, password_hash, has_picture, created_at, the
#                    body data (null = not set): height_cm, weight_kg, birth_year,
#                    sex ('male', 'female' or 'other'), active_plan_id: the workout plan
#                    followed in the gym sessions (null = none), and the look of the app chosen by
#                    the user: accent (favorite color, one of ACCENTS) and theme ('dark' or 'light')
#   usernames/{key}  {user_id}: one for each username (key = hash of the username in lowercase).
#   emails/{key}     {user_id}: the same for the emails. They keep usernames and emails unique,
#                    also ignoring upper/lower case, and let the login find the user of an email.
#   tokens/{hash}    {user_id, created_at}: one for each logged-in device. Only the hash of the
#                    token is stored, so a leaked database does not leak valid tokens.
#   posts/{id}       a post published by a user on their profile: id, user_id, kind, title,
#                    created_at and, depending on the kind:
#                      manual: a result added by hand, e.g. Deadlift - weight - 150 kg - higher is
#                              better: metric ('time' or 'weight'), better ('higher' or 'lower'),
#                              amount (seconds or kg), notes
#                      gym:    a finished gym session: duration_s, session_start, exercises
#                      run:    a finished run session: duration_s, session_start, distance_km
#                              (rounded to 0.1 km) and, if known, kcal, max_speed_kmh, goal_km,
#                              route (list of lines, as Google encoded polylines)
#                    session_id: the session of sessions/ that was published (if any)
#   sessions/{id}    a finished training session of a user (LAST SESSIONS and calendar of the
#                    app): id, user_id, type ('gym' or 'run'), client_id (chosen by the app: the
#                    same session sent twice is saved once), start_ms, duration_ms (milliseconds),
#                    published_post_id (null = not published), created_at and, depending on the type:
#                      run: distance_km, kcal, max_speed_kmh, goal_km, route (as in posts)
#                      gym: exercises (as done in the session: name, muscle_group, sets, reps,
#                           weight_kg, rest_s, notes, done), plan_id, plan_name, plan_day (index of
#                           the day of the plan), plan_day_name (null: session without a plan)
#   pictures/{id}    {jpeg, updated_at}: the profile picture of the user with that id
#   plans/{id}       a gym workout plan of a user, written by the AI coach and then changed by the
#                    user or by the coach: id, user_id, name, notes (advice of the coach), days
#                    (list of {name, exercises}; an exercise: name, muscle_group, sets, reps,
#                    rest_s, notes), what the user asked when creating it (goal, level,
#                    days_per_week, minutes_per_session, preferences), created_at, updated_at
#   counters/{name}  {last}: last id given to the users / posts / plans / sessions. The ids are numbers 1, 2, 3...
#                    (like AUTOINCREMENT in SQL), the same ids used by the app.

class DatabaseNotSetUp(Exception):
    """The key of the Firebase service account is missing or invalid."""


_database = None


def database():
    """The Firestore database: connected at the first request, then reused."""
    global _database
    if _database is None:
        if os.environ.get("FIRESTORE_EMULATOR_HOST"):
            # tests on this PC with the Firestore emulator of Firebase: no key needed
            _database = firestore.Client(project=os.environ.get("GOOGLE_CLOUD_PROJECT", "demo-gymbro"))
        else:
            try:
                _database = firestore.Client.from_service_account_info(service_account())
            except ValueError as e:
                raise DatabaseNotSetUp(f"invalid key of the Firebase service account: {e}")
    return _database


def service_account():
    """The key of the Firebase service account (the JSON downloaded from the Firebase console)."""
    text = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    if not text and os.path.exists(FIREBASE_KEY_FILE):
        # utf-8-sig: also files saved with a BOM (e.g. by some Windows editors)
        with open(FIREBASE_KEY_FILE, encoding="utf-8-sig") as file:
            text = file.read()
    if not text:
        raise DatabaseNotSetUp("FIREBASE_SERVICE_ACCOUNT / firebase_key.json missing")
    try:
        return json.loads(text)
    except ValueError:
        raise DatabaseNotSetUp("the key of the Firebase service account is not valid JSON")


def lookup_key(value):
    """Id of the usernames/emails documents: hash of the value in lowercase (any character is
    allowed, also '/', which Firestore ids cannot contain)."""
    return hashlib.sha256(value.lower().encode()).hexdigest()


def new_id(transaction, counter):
    """Next id of the users or posts. Inside a transaction: two requests arriving at the same time
    never get the same id."""
    ref = database().collection("counters").document(counter)
    snapshot = ref.get(transaction=transaction)
    last = snapshot.to_dict().get("last", 0) if snapshot.exists else 0
    transaction.set(ref, {"last": last + 1})
    return last + 1


def get_user(user_id):
    """The user with this id (a dict), or None."""
    snapshot = database().collection("users").document(str(user_id)).get()
    return snapshot.to_dict() if snapshot.exists else None


@firestore.transactional
def create_user(transaction, username, email, password_hash):
    """The new user, or None if the username or the email are already registered. A transaction:
    the checks and the writes happen together."""
    db = database()
    username_ref = db.collection("usernames").document(lookup_key(username))
    email_ref = db.collection("emails").document(lookup_key(email))
    if username_ref.get(transaction=transaction).exists or email_ref.get(transaction=transaction).exists:
        return None

    user_id = new_id(transaction, "users")
    user = {
        "id": user_id,
        "username": username,
        "email": email,
        "password_hash": password_hash,
        "has_picture": False,
        "created_at": now_iso(),
        "height_cm": None,
        "weight_kg": None,
        "birth_year": None,
        "sex": None,
        # the look of the new accounts: the user can change it later ("Customize account")
        "accent": DEFAULT_ACCENT,
        "theme": DEFAULT_THEME,
    }
    transaction.set(db.collection("users").document(str(user_id)), user)
    transaction.set(username_ref, {"user_id": user_id})
    transaction.set(email_ref, {"user_id": user_id})
    return user


@firestore.transactional
def insert_numbered(transaction, collection, document):
    """Saves a new post or plan with the next id of its collection (counter of the same name)."""
    document["id"] = new_id(transaction, collection)
    transaction.set(database().collection(collection).document(str(document["id"])), document)
    return document


# ------------------------------------------------------------------ HELPERS

USERNAME_PATTERN = re.compile(r"^[A-Za-z0-9_.]{3,30}$")
# Favorite colors of the app (the same names in the app: Accent in Appearance.kt) and themes
ACCENTS = ("blue", "violet", "pink", "red", "orange", "amber", "green", "teal")
THEMES = ("dark", "light")
DEFAULT_ACCENT = "blue"
DEFAULT_THEME = "dark"
EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def error(status, message):
    return jsonify({"error": message}), status


def hash_token(token):
    return hashlib.sha256(token.encode()).hexdigest()


def create_token(user_id):
    token = secrets.token_urlsafe(32)
    database().collection("tokens").document(hash_token(token)).set(
        {"user_id": user_id, "created_at": now_iso()}
    )
    return token


def user_json(user, private=False):
    """private=True: also email and body data (only for the user themself)."""
    data = {
        "id": user["id"],
        "username": user["username"],
        "has_picture": bool(user.get("has_picture")),
        "created_at": user["created_at"],
    }
    if private:
        data["email"] = user["email"]
        data["height_cm"] = user.get("height_cm")
        data["weight_kg"] = user.get("weight_kg")
        data["birth_year"] = user.get("birth_year")
        data["sex"] = user.get("sex")
        data["active_plan_id"] = user.get("active_plan_id")
        # accounts created before these existed: the default look
        data["accent"] = user.get("accent") or DEFAULT_ACCENT
        data["theme"] = user.get("theme") or DEFAULT_THEME
    return data


def post_json(post):
    keys = ("id", "user_id", "kind", "title", "metric", "better", "amount", "notes", "duration_s",
            "distance_km", "kcal", "max_speed_kmh", "goal_km", "route", "exercises", "session_start",
            "created_at")
    # the values not stored (e.g. the distance of a gym session) are null
    return {key: post.get(key) for key in keys}


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
    """The exercises of a gym session as they were done (sets, reps, weight, rest, notes, done),
    validated, or None if invalid. reps: a text, e.g. "10", "8-12", "30 s" (numbers also accepted)."""
    if not isinstance(exercises, list) or len(exercises) > 50:
        return None
    cleaned = []
    for exercise in exercises:
        if not isinstance(exercise, dict):
            return None
        name = clean_text(exercise.get("name"), 60)
        sets = number(exercise, "sets", 1, 100)
        reps = clean_text(exercise.get("reps"), 20)
        ok_weight, weight = optional_number(exercise, "weight_kg", 0, 1000)
        ok_rest, rest = optional_number(exercise, "rest_s", 0, 900)
        if name is None or sets is None or reps is None or not ok_weight or not ok_rest:
            return None
        cleaned.append({
            "name": name,
            "muscle_group": clean_text(exercise.get("muscle_group"), 60) or "",
            "sets": int(sets),
            "reps": reps,
            "weight_kg": weight,
            "rest_s": None if rest is None else int(rest),
            "notes": clean_text(exercise.get("notes"), 200) or "",
            # exercises of the sessions recorded before this information existed: done
            "done": exercise.get("done") is not False,
        })
    return cleaned


def login_required(view):
    """Lets the request through only with a valid 'Authorization: Bearer <token>' header."""

    @wraps(view)
    def wrapper(*args, **kwargs):
        header = request.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return error(401, "Login required")
        token_hash = hash_token(header[len("Bearer "):])
        token = database().collection("tokens").document(token_hash).get()
        user = get_user(token.get("user_id")) if token.exists else None
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
    return error(413, "The file is too big (max 4 MB)")


@app.errorhandler(DatabaseNotSetUp)
def database_not_set_up(e):
    return error(503, f"The database is not set up on the server: {e} (see server/README.md)")


@app.errorhandler(GoogleAPIError)
@app.errorhandler(GoogleAuthError)
def database_error(e):
    # e.g. Firestore not reachable, database not created, key of a deleted service account
    return error(503, f"Database error: {e}")


@app.errorhandler(500)
def server_error(_e):
    return error(500, "Server error")


# ------------------------------------------------------------------ ENDPOINTS

@app.get("/api/health")
def health():
    """Also checks the connection to the database (e.g. after setting up the key on Vercel)."""
    database().collection("counters").document("users").get()
    return jsonify({"status": "ok", "database": "ok"})


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

    password_hash = generate_password_hash(password, method="pbkdf2:sha256")
    user = create_user(database().transaction(), username, email, password_hash)
    if user is None:
        return error(409, "Username or email already registered")
    return jsonify({"token": create_token(user["id"]), "user": user_json(user, private=True)}), 201


@app.post("/api/login")
def login():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email", "")).strip()
    password = str(data.get("password", ""))

    email_doc = database().collection("emails").document(lookup_key(email)).get()
    user = get_user(email_doc.get("user_id")) if email_doc.exists else None
    if user is None or not check_password_hash(user["password_hash"], password):
        return error(401, "Wrong email or password")

    return jsonify({"token": create_token(user["id"]), "user": user_json(user, private=True)})


@app.post("/api/logout")
@login_required
def logout():
    database().collection("tokens").document(g.token_hash).delete()
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

    database().collection("users").document(str(g.user["id"])).update(profile)
    return jsonify(user_json({**g.user, **profile}, private=True))


@app.put("/api/me/appearance")
@login_required
def update_appearance():
    """The look of the app chosen by the user, the same on all their devices:
    {"accent": "blue", "theme": "dark"} (accent: one of ACCENTS; theme: 'dark' or 'light')."""
    data = request.get_json(silent=True) or {}
    appearance = {"accent": data.get("accent"), "theme": data.get("theme")}
    if appearance["accent"] not in ACCENTS or appearance["theme"] not in THEMES:
        return error(400, "Invalid favorite color or theme")
    database().collection("users").document(str(g.user["id"])).update(appearance)
    return jsonify(user_json({**g.user, **appearance}, private=True))


@app.put("/api/me/picture")
@login_required
def upload_picture():
    image = request.get_data()
    is_jpeg = image.startswith(b"\xff\xd8\xff")
    if not is_jpeg:
        return error(400, "The picture must be a JPEG image")
    if len(image) > MAX_PICTURE_BYTES:
        return error(413, "The picture is too big (max 900 KB)")

    db = database()
    batch = db.batch()  # both writes or none
    batch.set(db.collection("pictures").document(str(g.user["id"])), {"jpeg": image, "updated_at": now_iso()})
    batch.update(db.collection("users").document(str(g.user["id"])), {"has_picture": True})
    batch.commit()
    return "", 204


@app.get("/api/users/<int:user_id>")
def public_profile(user_id):
    user = get_user(user_id)
    if user is None:
        return error(404, "User not found")
    return jsonify(user_json(user))


@app.get("/api/users/<int:user_id>/picture")
def get_picture(user_id):
    picture = database().collection("pictures").document(str(user_id)).get()
    if not picture.exists:
        return error(404, "No profile picture")
    return Response(picture.get("jpeg"), mimetype="image/jpeg", headers={"Cache-Control": "no-cache"})


@app.get("/api/users/<int:user_id>/posts")
def user_posts(user_id):
    documents = database().collection("posts").where(filter=firestore.FieldFilter("user_id", "==", user_id)).stream()
    # newest first (sorted here: sorting in the query would need a composite index in Firestore)
    posts = sorted((document.to_dict() for document in documents),
                   key=lambda post: (post["created_at"], post["id"]), reverse=True)
    return jsonify([post_json(post) for post in posts])


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
        # gym: e.g. "Gym · Day 1 · Push" (the day of the plan followed)
        post["title"] = (clean_text(data.get("title"), 60) or "Gym session") if kind == "gym" else "Run"
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
                post["route"] = clean_route(data["route"])
                if post["route"] is None:
                    return error(400, "Invalid route")
        else:
            post["exercises"] = clean_exercises(data.get("exercises"))
            if post["exercises"] is None:
                return error(400, "Invalid exercises")

    else:
        return error(400, "The kind of post must be 'manual', 'gym' or 'run'")

    # the session of LAST SESSIONS that is published (optional): it is marked as published
    session_ref = None
    if kind in ("gym", "run") and data.get("session_id") is not None:
        session_ref, session = own_document("sessions", data["session_id"])
        if session is None:
            return error(404, "Session not found")
        if session.get("published_post_id") is not None:
            return error(409, "The session is already published")
        post["session_id"] = session["id"]

    post["user_id"] = g.user["id"]
    post["created_at"] = now_iso()
    # only the known values are stored (e.g. no kcal for the runs recorded before they existed)
    post = {key: value for key, value in post.items() if value is not None}
    saved = insert_numbered(database().transaction(), "posts", post)
    if session_ref is not None:
        session_ref.update({"published_post_id": saved["id"]})
    return jsonify(post_json(saved)), 201


def own_document(collection, document_id):
    """(reference, data) of a document of the logged user (post, session, plan), or (None, None)
    if it does not exist or belongs to someone else."""
    if isinstance(document_id, bool) or not isinstance(document_id, int):
        return None, None
    ref = database().collection(collection).document(str(document_id))
    snapshot = ref.get()
    if not snapshot.exists or snapshot.get("user_id") != g.user["id"]:
        return None, None
    return ref, snapshot.to_dict()


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
    ref, post = own_document("posts", post_id)
    if post is None:
        return error(404, "Post not found")
    ref.delete()
    # the session of the post can be published again
    if post.get("session_id") is not None:
        session_ref, session = own_document("sessions", post["session_id"])
        if session is not None and session.get("published_post_id") == post_id:
            session_ref.update({"published_post_id": None})
    return "", 204


# ------------------------------------------------------------------ TRAINING SESSIONS (LAST SESSIONS and calendar)

def session_json(session):
    keys = ("id", "type", "client_id", "start_ms", "duration_ms", "distance_km", "kcal", "max_speed_kmh",
            "goal_km", "route", "exercises", "plan_id", "plan_name", "plan_day", "plan_day_name",
            "published_post_id", "created_at")
    return {key: session.get(key) for key in keys}


def clean_session(data):
    """A finished training session sent by the app, validated: (session, None) or (None, problem)."""
    kind = data.get("type")
    if kind not in ("gym", "run"):
        return None, "The type of session must be 'gym' or 'run'"
    client_id = clean_text(data.get("client_id"), 64)
    # not in the future (one day of margin for a wrong clock of the phone), at most one week long
    start = number(data, "start_ms", 0, time.time() * 1000 + 24 * 3600 * 1000)
    duration = number(data, "duration_ms", 0, 7 * 24 * 3600 * 1000)
    if client_id is None or start is None or duration is None:
        return None, "Invalid session"
    session = {"type": kind, "client_id": client_id, "start_ms": int(start), "duration_ms": int(duration)}

    if kind == "run":
        for key, minimum, maximum in (("distance_km", 0, 1000), ("kcal", 0, 100_000), ("max_speed_kmh", 0, 200),
                                      ("goal_km", 0.01, 1000)):
            ok, session[key] = optional_number(data, key, minimum, maximum)
            if not ok:
                return None, f"Invalid {key}"
        if data.get("route") is not None:
            session["route"] = clean_route(data["route"])
            if session["route"] is None:
                return None, "Invalid route"
    else:
        session["exercises"] = clean_exercises(data.get("exercises") or [])
        ok_plan, plan_id = optional_number(data, "plan_id", 1, 10 ** 12)
        ok_day, plan_day = optional_number(data, "plan_day", 0, MAX_PLAN_DAYS - 1)
        if session["exercises"] is None:
            return None, "Invalid exercises"
        if not ok_plan or not ok_day:
            return None, "Invalid plan"
        # the plan and the day followed (the plan can be changed or deleted later: name and day
        # are kept as they were)
        session["plan_id"] = None if plan_id is None else int(plan_id)
        session["plan_name"] = clean_text(data.get("plan_name"), 60)
        session["plan_day"] = None if plan_day is None else int(plan_day)
        session["plan_day_name"] = clean_text(data.get("plan_day_name"), 40)
    return session, None


@app.get("/api/me/sessions")
@login_required
def my_sessions():
    """The finished sessions of the logged user, newest first (sorted here: no composite index needed)."""
    query = database().collection("sessions").where(filter=firestore.FieldFilter("user_id", "==", g.user["id"]))
    sessions = sorted((snapshot.to_dict() for snapshot in query.stream()),
                      key=lambda session: (session["start_ms"], session["id"]), reverse=True)
    return jsonify([session_json(session) for session in sessions])


@app.post("/api/me/sessions")
@login_required
def save_session():
    """A finished session, sent by the app when the session stops (or later, if the server was not
    reachable). The same client_id sent again (e.g. the answer was lost) does not save it twice.
    published_post_id (optional): the post of a session published before it was saved online."""
    data = request.get_json(silent=True) or {}
    session, problem = clean_session(data)
    if session is None:
        return error(400, problem)

    sessions = database().collection("sessions")
    existing = (sessions.where(filter=firestore.FieldFilter("user_id", "==", g.user["id"]))
                .where(filter=firestore.FieldFilter("client_id", "==", session["client_id"])).limit(1).get())
    if existing:
        return jsonify(session_json(existing[0].to_dict()))

    post_ref, post = own_document("posts", data.get("published_post_id"))
    session["published_post_id"] = post["id"] if post is not None else None
    session["user_id"] = g.user["id"]
    session["created_at"] = now_iso()
    saved = insert_numbered(database().transaction(), "sessions", session)
    if post_ref is not None:
        post_ref.update({"session_id": saved["id"]})
    return jsonify(session_json(saved)), 201


@app.delete("/api/me/sessions/<int:session_id>")
@login_required
def delete_session(session_id):
    """The session is removed from LAST SESSIONS; if it was published, the post stays on the profile."""
    ref, session = own_document("sessions", session_id)
    if session is None:
        return error(404, "Session not found")
    ref.delete()
    return "", 204


# ------------------------------------------------------------------ ACCOUNT

@firestore.transactional
def change_login(transaction, user, changes):
    """Changes username and/or email ({"username": ..., "email": ...}) keeping them unique.
    Returns the changes, or None if a new value is already registered by another user."""
    db = database()
    keys = {"username": "usernames", "email": "emails"}
    new_refs = {}
    # in a transaction all the reads come before the writes
    for field, value in changes.items():
        new_ref = db.collection(keys[field]).document(lookup_key(value))
        snapshot = new_ref.get(transaction=transaction)
        if snapshot.exists and snapshot.get("user_id") != user["id"]:
            return None
        new_refs[field] = new_ref
    for field, new_ref in new_refs.items():
        old_ref = db.collection(keys[field]).document(lookup_key(user[field]))
        if old_ref.id != new_ref.id:  # the same key if only upper/lower case changes
            transaction.delete(old_ref)
        transaction.set(new_ref, {"user_id": user["id"]})
    if changes:
        transaction.update(db.collection("users").document(str(user["id"])), changes)
    return changes


@app.put("/api/me/account")
@login_required
def update_account():
    """Username, email and password of the logged user: {"current_password": "...", "username": "...",
    "email": "...", "new_password": "..."} (username, email and new_password optional: missing or
    empty = unchanged). The current password is always needed. After a new password, the other
    devices are logged out."""
    data = request.get_json(silent=True) or {}
    if not check_password_hash(g.user["password_hash"], str(data.get("current_password", ""))):
        return error(403, "Wrong current password")
    username = str(data.get("username") or "").strip() or None
    email = str(data.get("email") or "").strip() or None
    new_password = str(data.get("new_password") or "") or None
    if username is not None and not USERNAME_PATTERN.match(username):
        return error(400, "The username must be 3-30 characters: letters, numbers, '_' or '.'")
    if email is not None and not EMAIL_PATTERN.match(email):
        return error(400, "Invalid email address")
    if new_password is not None and len(new_password) < 6:
        return error(400, "The new password must be at least 6 characters")

    wanted = {field: value for field, value in (("username", username), ("email", email))
              if value is not None and value != g.user[field]}
    changes = change_login(database().transaction(), g.user, wanted)
    if changes is None:
        return error(409, "Username or email already registered")

    if new_password is not None:
        db = database()
        db.collection("users").document(str(g.user["id"])).update(
            {"password_hash": generate_password_hash(new_password, method="pbkdf2:sha256")})
        # the other devices must log in again with the new password
        batch = db.batch()
        tokens = db.collection("tokens").where(filter=firestore.FieldFilter("user_id", "==", g.user["id"])).stream()
        for token in tokens:
            if token.id != g.token_hash:
                batch.delete(token.reference)
        batch.commit()
    return jsonify(user_json({**g.user, **changes}, private=True))


@app.post("/api/me/delete-account")
@login_required
def delete_account():
    """Deletes the account of the logged user and all their data: profile, logins, posts, sessions,
    workout plans and profile picture. {"password": "..."} to confirm."""
    data = request.get_json(silent=True) or {}
    if not check_password_hash(g.user["password_hash"], str(data.get("password", ""))):
        return error(403, "Wrong password")

    db = database()
    user_id = g.user["id"]

    def documents_of(collection):
        query = db.collection(collection).where(filter=firestore.FieldFilter("user_id", "==", user_id))
        return [snapshot.reference for snapshot in query.stream()]

    # the account itself and the logins at the end: if something fails before, the user can try again
    refs = documents_of("posts") + documents_of("sessions") + documents_of("plans") + [
        db.collection("pictures").document(str(user_id)),
        db.collection("usernames").document(lookup_key(g.user["username"])),
        db.collection("emails").document(lookup_key(g.user["email"])),
        db.collection("users").document(str(user_id)),
    ] + documents_of("tokens")
    # a batch writes at most 500 documents
    for start in range(0, len(refs), 400):
        batch = db.batch()
        for ref in refs[start:start + 400]:
            batch.delete(ref)
        batch.commit()
    return "", 204


# ------------------------------------------------------------------ AI COACH (chat page of the app)

# Instructions given to the model before the conversation (the "system prompt")
COACH_INSTRUCTIONS = """You are GymBro Coach, the personal trainer inside GymBro, an app for gym training and running.
You help the user with:
- how to perform the exercises correctly: step-by-step execution, breathing, common mistakes, safety tips
- which exercises to choose for a muscle group, a goal or the available equipment, and their alternatives
- how to organize the training: weekly frequency, splits, sets, reps, rest, progression, warm-up, deload
- running: pace, distances, training plans, technique, warm-up and cool-down
- recovery, sleep, and general nutrition and hydration for training

Rules:
- Always answer in the language of the last message of the user.
- Be practical and concise: short paragraphs and lists. If important information is missing (level, goal, days per week, equipment, injuries), give a useful general answer and ask for it.
- Formatting: plain text, with only **bold**, "- " bullet lists and "1. " numbered lists. No tables, no titles with #, no code.
- You are not a doctor: for pain, injuries, illnesses, pregnancy, medicines or eating disorders give only general information and suggest seeing a doctor or a physiotherapist. Never suggest doping, steroids or extreme diets.
- If the request has nothing to do with training, sport, health or nutrition, kindly say that you can help only with training and running.
- You cannot see the data of the user in the app: ask for them if you need them.
- In this chat you cannot create or change the workout plans of the user. The user can create a gym plan with you in the Profile page of the app (WORKOUT PLANS > New), and change it with you from the page of the plan (button "Ask the coach")."""


class ChatError(Exception):
    """A model could not answer (the message says why).
    rate_limited: its free limits are used up for now (retry_after: seconds to wait, if known).
    bad_key: the key of the service is not valid, so its other models would fail too."""

    def __init__(self, message, rate_limited=False, retry_after=None, bad_key=False):
        super().__init__(message)
        self.rate_limited = rate_limited
        self.retry_after = retry_after
        self.bad_key = bad_key


class CoachUnavailable(Exception):
    """No model could answer: status is the HTTP status for the app (429 if they are all busy)."""

    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


@app.errorhandler(CoachUnavailable)
def coach_unavailable(e):
    return error(e.status, str(e))


def chat_providers():
    """The chat services that are set up, in order of use: (name, key, url, models)."""
    providers = []
    for name, key_variable, key_file, url, models_variable, default_models in (
        ("Groq", "GROQ_API_KEY", GROQ_KEY_FILE, GROQ_URL, "GROQ_MODELS", GROQ_MODELS),
        ("Gemini", "GEMINI_API_KEY", GEMINI_KEY_FILE, GEMINI_URL, "GEMINI_MODELS", GEMINI_MODELS),
    ):
        key = api_key(key_variable, key_file)
        if key:
            models = os.environ.get(models_variable, "").strip() or default_models
            providers.append((name, key, url, [model.strip() for model in models.split(",") if model.strip()]))
    return providers


def reasoning_options(model):
    """Options of the models that reason before answering: little reasoning (faster answers, fewer
    tokens used) and not included in the answer."""
    if model.startswith("openai/gpt-oss"):
        return {"reasoning_effort": "low", "include_reasoning": False}
    if model.startswith("qwen/"):
        return {"reasoning_effort": "none", "reasoning_format": "hidden"}
    if model.startswith("gemini-"):
        return {"reasoning_effort": "low"}
    return {}


def ask_model(provider, key, url, model, messages, timeout, max_tokens, json_schema=None):
    """The answer of one model to the conversation (OpenAI-compatible chat API), or raises ChatError.
    json_schema: (name, schema) of the JSON object the answer must be (structured outputs)."""
    body = {"model": model, "messages": messages, "max_tokens": max_tokens, **reasoning_options(model)}
    if json_schema is not None:
        name, schema = json_schema
        body["response_format"] = {"type": "json_schema", "json_schema": {"name": name, "strict": True, "schema": schema}}
    service_request = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
            "User-Agent": "GymBro/1.0 (university project)",
        },
    )
    try:
        with urllib.request.urlopen(service_request, timeout=timeout) as response:
            answer = json.load(response)
    except urllib.error.HTTPError as e:
        message = f"{provider} {model}: {error_message(e)}"
        if e.code == 429:
            raise ChatError(message, rate_limited=True, retry_after=retry_seconds(e.headers.get("Retry-After")))
        raise ChatError(message, bad_key=e.code in (401, 403))
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        raise ChatError(f"{provider} {model}: not reachable or no answer in {timeout:.0f} s")

    try:
        choice = answer["choices"][0]
        text = choice["message"].get("content") or ""
    except (KeyError, IndexError, TypeError, AttributeError):
        raise ChatError(f"{provider} {model}: invalid answer")
    # some models write their reasoning between <think> tags before the answer (removed, also if
    # the answer was cut before the closing tag)
    text = re.sub(r"<think>.*?(</think>|$)", "", text, flags=re.DOTALL).strip()
    if not text:
        raise ChatError(f"{provider} {model}: empty answer ({choice.get('finish_reason')})")
    return text


def ask_coach(messages, max_tokens=MAX_ANSWER_TOKENS, json_schema=None, read_answer=None,
              timeout_s=None, deadline_s=None):
    """The answer of the first model that answers, among the models of Groq and then of Gemini (see
    chat_providers): (answer, model, provider). When a model has used up its free limits, the next
    one answers. read_answer: turns the text of the answer into the value returned (e.g. a plan);
    it raises ChatError if the answer is not valid, and then the next model is asked.
    timeout_s / deadline_s: for each model / for all of them (default: those of the chat).
    Raises CoachUnavailable if no model answers."""
    timeout_s = timeout_s or CHAT_TIMEOUT_S
    deadline_s = deadline_s or CHAT_DEADLINE_S
    providers = chat_providers()
    if not providers:
        raise CoachUnavailable(503, "The AI coach is not set up on the server: GROQ_API_KEY missing (see server/README.md)")

    deadline = time.monotonic() + deadline_s
    problems = []
    for provider, key, url, models in providers:
        for model in models:
            remaining = deadline - time.monotonic()
            if remaining < 5:
                break
            try:
                text = ask_model(provider, key, url, model, messages, min(timeout_s, remaining), max_tokens, json_schema)
                try:
                    answer = read_answer(text) if read_answer else text
                except ChatError as e:
                    raise ChatError(f"{provider} {model}: {e}")
            except ChatError as e:
                # visible in the logs of Vercel (project > Logs)
                app.logger.warning("Chat: %s", e)
                problems.append(e)
                if e.bad_key:
                    break
                continue
            return answer, model, provider

    if problems and all(problem.rate_limited for problem in problems):
        waits = [problem.retry_after for problem in problems if problem.retry_after]
        raise CoachUnavailable(429, "The coach is very busy: the free limits of the AI models are used up. "
                                    f"Try again {wait_text(min(waits) if waits else None)}.")
    raise CoachUnavailable(502, "The AI coach is not available: " + "; ".join(str(problem) for problem in problems))


def retry_seconds(value):
    """The Retry-After header of a 429 answer, in seconds (None if missing)."""
    try:
        return max(1, round(float(value)))
    except (TypeError, ValueError):
        return None


def wait_text(seconds):
    if seconds is None:
        return "in a few minutes"
    if seconds < 90:
        return f"in {seconds} seconds"
    if seconds < 90 * 60:
        return f"in {round(seconds / 60)} minutes"
    return f"in {round(seconds / 3600)} hours"


def clean_conversation(messages, max_chars=CHAT_HISTORY_CHARS):
    """The conversation sent by the app, validated (None if invalid) and limited to its newest
    messages that fit in max_chars characters. It must end with a message of the user."""
    if not isinstance(messages, list) or not 1 <= len(messages) <= 200:
        return None
    cleaned = []
    for message in messages:
        if not isinstance(message, dict) or message.get("role") not in ("user", "assistant"):
            return None
        content = message.get("content")
        if not isinstance(content, str) or not content.strip() or len(content) > MAX_CHAT_MESSAGE_CHARS:
            return None
        cleaned.append({"role": message["role"], "content": content.strip()})
    if cleaned[-1]["role"] != "user":
        return None

    kept = []
    total = 0
    for message in reversed(cleaned):
        total += len(message["content"])
        if kept and total > max_chars:
            break
        kept.insert(0, message)
    # the conversation given to the model starts with a message of the user
    while kept[0]["role"] != "user":
        kept.pop(0)
    return kept


@app.post("/api/chat")
@login_required
def chat():
    """Answer of the AI coach (see ask_coach). The conversation is saved only on the phone: the app
    sends it every time, so any model can continue it.
    Body: {"messages": [{"role": "user" | "assistant", "content": "..."}, ...], "plan_id": 3}
    -> {"reply": "...", "model": "openai/gpt-oss-120b", "provider": "Groq"}
    With plan_id (optional): chat about that workout plan of the user, which the coach sees and can
    change; the answer also has "plan": the plan changed by the coach (already saved), or null."""
    data = request.get_json(silent=True) or {}
    plan_id = data.get("plan_id")
    if plan_id is not None:
        return plan_chat(plan_id, data.get("messages"))

    conversation = clean_conversation(data.get("messages"))
    if conversation is None:
        return error(400, "Invalid messages")
    messages = [{"role": "system", "content": COACH_INSTRUCTIONS}] + conversation
    reply, model, provider = ask_coach(messages)
    return jsonify({"reply": reply, "model": model, "provider": provider})


# ------------------------------------------------------------------ WORKOUT PLANS (written by the AI coach)

# Goals of a plan, as written in the requests of the app -> as explained to the model
PLAN_GOALS = {
    "lose_weight": "lose weight (lose fat, keeping the muscles)",
    "build_muscle": "build muscle (hypertrophy), staying lean",
    "gain_mass": "gain mass (more muscle and more body weight)",
    "strength": "get stronger (strength)",
    "fitness": "general fitness and health",
}
PLAN_LEVELS = ("beginner", "intermediate", "advanced")
MAX_PLANS = 20                  # plans saved by each user
MAX_PLAN_DAYS = 7
MAX_DAY_EXERCISES = 15
# A plan is a long answer: more tokens and more time than a chat message. Groq counts the
# maximum answer in the tokens per minute of the request (8000 for the free models): the
# instructions, the plan, the conversation and the answer must stay under it.
PLAN_ANSWER_TOKENS = 3_500
PLAN_CHAT_ANSWER_TOKENS = 3_000
PLAN_CHAT_HISTORY_CHARS = 6_000
PLAN_TIMEOUT_S = 40
PLAN_DEADLINE_S = 75   # the app waits 90 s for a plan

# JSON schema of a plan written by the model (structured outputs: the model must follow it).
# Strict mode: every field is required, no other fields.
PLAN_EXERCISE_SCHEMA = {
    "type": "object",
    "properties": {
        "name": {"type": "string"},
        "muscle_group": {"type": "string"},
        "sets": {"type": "integer"},
        "reps": {"type": "string"},
        "rest_s": {"type": "integer"},
        "weight_kg": {"type": ["number", "null"]},
        "notes": {"type": "string"},
    },
    "required": ["name", "muscle_group", "sets", "reps", "rest_s", "weight_kg", "notes"],
    "additionalProperties": False,
}
PLAN_SCHEMA = {
    "type": "object",
    "properties": {
        "name": {"type": "string"},
        "notes": {"type": "string"},
        "days": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "exercises": {"type": "array", "items": PLAN_EXERCISE_SCHEMA},
                },
                "required": ["name", "exercises"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["name", "notes", "days"],
    "additionalProperties": False,
}
# Answer of the coach in the chat about a plan: the reply and the changed plan (null = not changed)
PLAN_CHAT_SCHEMA = {
    "type": "object",
    "properties": {
        "reply": {"type": "string"},
        "plan": {"anyOf": [PLAN_SCHEMA, {"type": "null"}]},
    },
    "required": ["reply", "plan"],
    "additionalProperties": False,
}

PLAN_RULES = """Rules for the plan:
- One day for each training day of the week, with a short name that says what it trains (e.g. "Day 1 · Push", "Day A · Full body").
- For each day, the exercises in the order to do them (compound exercises first), so many that the session fits in the minutes available, rest included (usually 4-8).
- For each exercise: its name, the main muscle group, the sets, the reps (a number, a range like "8-10", or a time like "30 s" or "20 min" for planks and cardio), the rest between the sets in seconds, the weight in kg ("weight_kg") only if the user told you the weight they use (otherwise null), and short notes only if useful (e.g. a technique tip, "per leg"); otherwise empty notes.
- Exercises, sets, reps and rest must fit the goal and the level of the user. Respect all their preferences and limits (exercises to avoid, available equipment, injuries): if an exercise is not possible, choose an alternative.
- "notes" of the plan: 2-4 short sentences of advice: warm-up, how to increase the weights, how to adapt the plan. If the body data need care (e.g. a high BMI, an older age), say it kindly here.
- Write names and notes in {language}. For the exercises, use the names commonly used in gyms (the English names are fine when they are the usual ones, e.g. Bench Press, Lat Machine).
- You are not a doctor: never suggest doping, steroids or extreme diets. If the user mentions pain or injuries, avoid the exercises that load that part and suggest a doctor or a physiotherapist in the notes."""

PLAN_CREATE_INSTRUCTIONS = """You are GymBro Coach, the personal trainer inside GymBro, an app for gym training and running.
Create a gym workout plan for the user, based on their body data, goal, level, available time and preferences.

""" + PLAN_RULES + """

Answer only with the JSON object of the plan:
{{"name": "short name of the plan", "notes": "...", "days": [{{"name": "...", "exercises": [{{"name": "...", "muscle_group": "...", "sets": 3, "reps": "8-10", "rest_s": 90, "notes": ""}}]}}]}}"""

PLAN_CHAT_INSTRUCTIONS = """You are GymBro Coach, the personal trainer inside GymBro, an app for gym training and running.
The user is looking at one of their gym workout plans in the app: they ask you questions about it or ask you to change it.

Body data of the user:
{body}
The user created the plan for: goal {goal}; level {level}; {days_per_week} training days per week; {minutes} minutes per session; preferences and limits: {preferences}.

The plan now (the user may have changed it by hand: this is always the latest version):
{plan}

Rules for your answer:
- "reply": the answer to the last message of the user, in the language of that message, practical and concise. Plain text, with only **bold**, "- " bullet lists and "1. " numbered lists.
- If the user asks to change the plan (replace, add or remove exercises or days, change sets, reps or rest, make it easier, harder, shorter...), change it and write the whole new plan in "plan", keeping as they are all the parts the user did not ask to change. In "reply" say briefly what you changed.
- If the plan does not need to change (questions, explanations, advice), "plan" is null.
- If the request has nothing to do with training, sport, health or nutrition, kindly say that you can help only with training and running, and "plan" is null.

""" + PLAN_RULES + """

Answer only with a JSON object: {{"reply": "...", "plan": null or the whole plan, with the same fields of the plan above}}"""


def body_summary(user):
    """What the coach knows about the user for the workout plans: the body data of the profile and
    the BMI. Never the username, the email or the password."""
    lines = []
    height = user.get("height_cm")
    weight = user.get("weight_kg")
    if height:
        lines.append(f"- height: {height:g} cm")
    if weight:
        lines.append(f"- weight: {weight:g} kg")
    if height and weight:
        bmi = weight / (height / 100) ** 2
        category = ("underweight" if bmi < 18.5 else "normal weight" if bmi < 25
                    else "overweight" if bmi < 30 else "obesity")
        lines.append(f"- BMI: {bmi:.1f} ({category})")
    if user.get("birth_year"):
        lines.append(f"- age: about {datetime.now(timezone.utc).year - user['birth_year']} years")
    if user.get("sex"):
        lines.append(f"- sex: {user['sex']}")
    return "\n".join(lines) if lines else "- not known (the user did not add them to the profile)"


def clean_text(value, max_length):
    """A text of a plan without spaces at the ends, cut to max_length characters (numbers are
    turned into texts, e.g. the reps 10); None if missing or empty."""
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        value = format(value, "g")
    if not isinstance(value, str) or not value.strip():
        return None
    return value.strip()[:max_length].strip()


def plan_number(value, minimum, maximum, whole=True):
    """A number of a plan (also written as a text, e.g. "90" or "22,5"), brought between minimum and
    maximum: whole, or with one decimal (whole=False); None if it is not a number."""
    if isinstance(value, str):
        try:
            value = float(value.strip().replace(",", "."))
        except ValueError:
            return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value != value:
        return None
    value = min(max(value, minimum), maximum)
    return int(round(value)) if whole else round(value, 1)


def clean_plan(data):
    """Name, notes and days (with their exercises) of a plan written by the coach or changed by the
    user, validated; None if invalid."""
    if not isinstance(data, dict):
        return None
    name = clean_text(data.get("name"), 60)
    days = data.get("days")
    if name is None or not isinstance(days, list) or not 1 <= len(days) <= MAX_PLAN_DAYS:
        return None
    cleaned_days = []
    for day in days:
        if not isinstance(day, dict):
            return None
        day_name = clean_text(day.get("name"), 40)
        exercises = day.get("exercises")
        if day_name is None or not isinstance(exercises, list) or len(exercises) > MAX_DAY_EXERCISES:
            return None
        cleaned_exercises = []
        for exercise in exercises:
            if not isinstance(exercise, dict):
                return None
            exercise_name = clean_text(exercise.get("name"), 60)
            sets = plan_number(exercise.get("sets"), 1, 20)
            reps = clean_text(exercise.get("reps"), 20)
            if exercise_name is None or sets is None or reps is None:
                return None
            rest = exercise.get("rest_s")
            weight = exercise.get("weight_kg")
            cleaned_exercises.append({
                "name": exercise_name,
                "muscle_group": clean_text(exercise.get("muscle_group"), 30) or "",
                "sets": sets,
                "reps": reps,
                # null: rest not set (e.g. left empty by the user)
                "rest_s": None if rest is None or rest == "" else plan_number(rest, 0, 900),
                # null: no weight (e.g. bodyweight exercises, or not known)
                "weight_kg": None if weight is None or weight == "" else plan_number(weight, 0, 1000, whole=False),
                "notes": clean_text(exercise.get("notes"), 200) or "",
            })
        cleaned_days.append({"name": day_name, "exercises": cleaned_exercises})
    return {"name": name, "notes": clean_text(data.get("notes"), 1000) or "", "days": cleaned_days}


def json_answer(text):
    """The JSON object written by the model (also if it is between ``` marks), or raises ChatError."""
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
    try:
        value = json.loads(text)
    except ValueError:
        raise ChatError("the answer is not valid JSON")
    if not isinstance(value, dict):
        raise ChatError("the answer is not a JSON object")
    return value


def read_new_plan(text):
    """The plan written by the model when creating a plan, or raises ChatError."""
    plan = clean_plan(json_answer(text))
    if plan is None or any(not day["exercises"] for day in plan["days"]):
        raise ChatError("the plan written is not valid")
    return plan


def read_plan_chat_answer(text):
    """(reply, plan changed by the model or None) of an answer in the chat about a plan, or raises
    ChatError."""
    answer = json_answer(text)
    reply = answer.get("reply")
    if not isinstance(reply, str) or not reply.strip():
        raise ChatError("the answer has no reply")
    plan = None
    if answer.get("plan") is not None:
        plan = clean_plan(answer["plan"])
        if plan is None:
            raise ChatError("the changed plan is not valid")
    return reply.strip(), plan


def plan_json(plan):
    keys = ("id", "name", "goal", "level", "days_per_week", "minutes_per_session", "preferences",
            "notes", "days", "created_at", "updated_at")
    return {key: plan.get(key) for key in keys}


def plans_of(user_id):
    """The plans of a user, the last changed first (no composite index needed: sorted here)."""
    query = database().collection("plans").where(filter=firestore.FieldFilter("user_id", "==", user_id))
    plans = [snapshot.to_dict() for snapshot in query.stream()]
    plans.sort(key=lambda plan: (plan.get("updated_at", ""), plan["id"]), reverse=True)
    return plans


def own_plan(plan_id):
    """(reference, plan) of a plan of the logged user, or (None, None) if it is not one of theirs."""
    return own_document("plans", plan_id)


@app.get("/api/me/plans")
@login_required
def my_plans():
    return jsonify([plan_json(plan) for plan in plans_of(g.user["id"])])


@app.post("/api/me/plans")
@login_required
def create_plan():
    """A new workout plan written by the AI coach for the logged user, from their body data (see
    body_summary) and what they ask: {"goal": "build_muscle", "level": "beginner",
    "days_per_week": 3, "minutes_per_session": 60, "preferences": "no squat", "language": "Italian"}
    goal: one of PLAN_GOALS; preferences and language optional."""
    data = request.get_json(silent=True) or {}
    goal = data.get("goal")
    level = data.get("level")
    days_per_week = number(data, "days_per_week", 1, MAX_PLAN_DAYS)
    minutes = number(data, "minutes_per_session", 15, 180)
    preferences = data.get("preferences") or ""
    language = data.get("language") or "English"
    if goal not in PLAN_GOALS or level not in PLAN_LEVELS or days_per_week is None or minutes is None:
        return error(400, "Invalid goal, level, days or minutes")
    if not isinstance(preferences, str) or len(preferences) > 500:
        return error(400, "Invalid preferences (max 500 characters)")
    if not isinstance(language, str) or not re.fullmatch(r"[A-Za-z ()-]{2,40}", language):
        return error(400, "Invalid language")
    if len(plans_of(g.user["id"])) >= MAX_PLANS:
        return error(400, f"You can save at most {MAX_PLANS} plans: delete one first")

    request_text = (f"Body data of the user:\n{body_summary(g.user)}\n\n"
                    f"Goal: {PLAN_GOALS[goal]}\nLevel: {level}\n"
                    f"Training days per week: {int(days_per_week)}\nMinutes per session: {int(minutes)}\n"
                    f"Preferences and limits: {preferences.strip() or 'none'}")
    messages = [
        {"role": "system", "content": PLAN_CREATE_INSTRUCTIONS.format(language=language)},
        {"role": "user", "content": request_text},
    ]
    plan, _model, _provider = ask_coach(messages, PLAN_ANSWER_TOKENS, ("workout_plan", PLAN_SCHEMA),
                                        read_new_plan, PLAN_TIMEOUT_S, PLAN_DEADLINE_S)
    plan.update({
        "user_id": g.user["id"],
        "goal": goal,
        "level": level,
        "days_per_week": int(days_per_week),
        "minutes_per_session": int(minutes),
        "preferences": preferences.strip(),
        "language": language,
        "created_at": now_iso(),
        "updated_at": now_iso(),
    })
    return jsonify(plan_json(insert_numbered(database().transaction(), "plans", plan))), 201


@app.get("/api/me/plans/<int:plan_id>")
@login_required
def get_plan(plan_id):
    _ref, plan = own_plan(plan_id)
    if plan is None:
        return error(404, "Plan not found")
    return jsonify(plan_json(plan))


@app.put("/api/me/plans/<int:plan_id>")
@login_required
def update_plan(plan_id):
    """The plan changed by the user: {"name": "...", "notes": "...", "days": [...]} (see clean_plan)."""
    ref, plan = own_plan(plan_id)
    if plan is None:
        return error(404, "Plan not found")
    changes = clean_plan(request.get_json(silent=True))
    if changes is None:
        return error(400, "Invalid plan")
    changes["updated_at"] = now_iso()
    ref.update(changes)
    plan.update(changes)
    return jsonify(plan_json(plan))


@app.delete("/api/me/plans/<int:plan_id>")
@login_required
def delete_plan(plan_id):
    ref, plan = own_plan(plan_id)
    if plan is None:
        return error(404, "Plan not found")
    ref.delete()
    if g.user.get("active_plan_id") == plan_id:
        database().collection("users").document(str(g.user["id"])).update({"active_plan_id": None})
    return "", 204


@app.put("/api/me/active-plan")
@login_required
def set_active_plan():
    """The plan followed in the gym sessions: {"plan_id": 3} (null: no active plan). Returns the user."""
    plan_id = (request.get_json(silent=True) or {}).get("plan_id")
    if plan_id is not None and own_plan(plan_id)[1] is None:
        return error(404, "Plan not found")
    database().collection("users").document(str(g.user["id"])).update({"active_plan_id": plan_id})
    return jsonify(user_json({**g.user, "active_plan_id": plan_id}, private=True))


def plan_chat(plan_id, messages):
    """Answer of the coach in the chat about a plan of the user (see chat). The coach sees the plan
    as it is now in the database, also after the changes made by the user."""
    ref, plan = own_plan(plan_id)
    if plan is None:
        return error(404, "Plan not found")
    conversation = clean_conversation(messages, PLAN_CHAT_HISTORY_CHARS)
    if conversation is None:
        return error(400, "Invalid messages")

    # only what the model needs, without spaces: fewer tokens
    plan_text = json.dumps({"name": plan["name"], "notes": plan["notes"], "days": plan["days"]},
                           ensure_ascii=False, separators=(",", ":"))
    instructions = PLAN_CHAT_INSTRUCTIONS.format(
        body=body_summary(g.user),
        goal=PLAN_GOALS.get(plan.get("goal"), "not set"),
        level=plan.get("level") or "not set",
        days_per_week=plan.get("days_per_week") or len(plan["days"]),
        minutes=plan.get("minutes_per_session") or "not set",
        preferences=plan.get("preferences") or "none",
        plan=plan_text,
        language=plan.get("language") or "the language of the plan",
    )
    messages = [{"role": "system", "content": instructions}] + conversation
    (reply, changed), model, provider = ask_coach(
        messages, PLAN_CHAT_ANSWER_TOKENS, ("plan_chat_answer", PLAN_CHAT_SCHEMA), read_plan_chat_answer,
        PLAN_TIMEOUT_S, PLAN_DEADLINE_S)

    if changed is not None:
        changed["updated_at"] = now_iso()
        ref.update(changed)
        plan.update(changed)
    return jsonify({"reply": reply, "model": model, "provider": provider,
                    "plan": plan_json(plan) if changed is not None else None})


if __name__ == "__main__":
    # 127.0.0.1: reachable only from this PC (and from the Android emulator, as 10.0.2.2)
    app.run(host="127.0.0.1", port=5000, debug=True)
