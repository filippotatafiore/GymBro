"""
GymBro REST API.

A small Flask server that stores the users of the app (with their body data) and the posts
they publish (results of finished gym/run sessions or results added manually) in Cloud Firestore,
the database of Firebase. It also computes the walking routes of the runs with OpenRouteService
(or the Google Routes API), so that the keys of these services stay on the server.

It runs online on Vercel, always reachable (see README.md). To run it on this PC instead
(the Android emulator reaches it at http://10.0.2.2:5000):
    python app.py
"""

import hashlib
import json
import os
import re
import secrets
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
# Biggest profile picture: a Firestore document holds at most 1 MiB (the app sends ~50-150 KB)
MAX_PICTURE_BYTES = 900_000

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 4 * 1024 * 1024  # biggest accepted request (Vercel: at most 4.5 MB)


# ------------------------------------------------------------------ DATABASE (Cloud Firestore)
#
# Firestore stores "documents" (similar to the rows of a SQL table) in "collections":
#
#   users/{id}       a user: id, username, email, password_hash, has_picture, created_at and the
#                    body data (null = not set): height_cm, weight_kg, birth_year,
#                    sex ('male', 'female' or 'other')
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
#   pictures/{id}    {jpeg, updated_at}: the profile picture of the user with that id
#   counters/{name}  {last}: last id given to the users / posts. The ids are numbers 1, 2, 3...
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
    }
    transaction.set(db.collection("users").document(str(user_id)), user)
    transaction.set(username_ref, {"user_id": user_id})
    transaction.set(email_ref, {"user_id": user_id})
    return user


@firestore.transactional
def insert_post(transaction, post):
    post["id"] = new_id(transaction, "posts")
    transaction.set(database().collection("posts").document(str(post["id"])), post)
    return post


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
                post["route"] = clean_route(data["route"])
                if post["route"] is None:
                    return error(400, "Invalid route")
        else:
            post["exercises"] = clean_exercises(data.get("exercises"))
            if post["exercises"] is None:
                return error(400, "Invalid exercises")

    else:
        return error(400, "The kind of post must be 'manual', 'gym' or 'run'")

    post["user_id"] = g.user["id"]
    post["created_at"] = now_iso()
    # only the known values are stored (e.g. no kcal for the runs recorded before they existed)
    post = {key: value for key, value in post.items() if value is not None}
    return jsonify(post_json(insert_post(database().transaction(), post))), 201


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
    ref = database().collection("posts").document(str(post_id))
    post = ref.get()
    if not post.exists or post.get("user_id") != g.user["id"]:
        return error(404, "Post not found")
    ref.delete()
    return "", 204


if __name__ == "__main__":
    # 127.0.0.1: reachable only from this PC (and from the Android emulator, as 10.0.2.2)
    app.run(host="127.0.0.1", port=5000, debug=True)
