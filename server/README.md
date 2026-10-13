# GymBro REST API

Flask server that stores the app users (registration / login, body data) and the posts they
publish, in a SQLite database (`gymbro.db`, created automatically on first start). It also computes
the walking routes of the runs with OpenRouteService or the Google Routes API (their keys stay on
the server).

| Method | Path                          | Auth | Description                          |
|--------|-------------------------------|------|--------------------------------------|
| GET    | `/api/health`                 |      | Check that the server is running     |
| POST   | `/api/register`               |      | `{username, email, password}`        |
| POST   | `/api/login`                  |      | `{email, password}`                  |
| POST   | `/api/logout`                 | yes  | Invalidates the token                |
| GET    | `/api/me`                     | yes  | Logged user (with email, body data)  |
| PUT    | `/api/me/profile`             | yes  | Body data (see below)                |
| PUT    | `/api/me/picture`             | yes  | Body: JPEG image                     |
| POST   | `/api/me/posts`               | yes  | Publish a post (see below)           |
| DELETE | `/api/me/posts/<id>`          | yes  | Delete one of your posts             |
| POST   | `/api/routes/walking`         | yes  | Walking route (see below)            |
| GET    | `/api/users/<id>`             |      | Public profile                       |
| GET    | `/api/users/<id>/picture`     |      | Profile picture (404 if none)        |
| GET    | `/api/users/<id>/posts`       |      | Posts published by the user          |

"Auth" endpoints need the header `Authorization: Bearer <token>` (the token is returned by
register/login).

Body of `POST /api/me/posts`, depending on the kind of post:

- manual result: `{"kind": "manual", "title": "Deadlift", "metric": "weight" | "time",
  "better": "higher" | "lower", "amount": 150, "notes": "optional"}` (amount: kg or seconds)
- run session: `{"kind": "run", "duration_s": 3600, "distance_km": 10.4, "session_start": "2026-10-12T08:00:00Z",
  "kcal": 780, "max_speed_kmh": 14.2, "goal_km": 10, "route": ["_p~iF~ps|U_ulLnnqC"]}`
  (kcal, max_speed_kmh, goal_km and route are optional; route = the lines followed, as
  [Google encoded polylines](https://developers.google.com/maps/documentation/utilities/polylinealgorithm))
- gym session: `{"kind": "gym", "duration_s": 2700, "session_start": "...", "exercises":
  [{"name": "Bench Press", "muscle_group": "Chest", "sets": 4, "reps": 8, "weight_kg": 60}]}`

Body of `PUT /api/me/profile` (every value optional, null = not set):
`{"height_cm": 180, "weight_kg": 72.5, "birth_year": 2001, "sex": "male" | "female" | "other"}`

Body of `POST /api/routes/walking`: `{"origin": {"lat": 45.46, "lng": 9.19}, "stop": null,
"destination": {"lat": 45.47, "lng": 9.18}}` (stop optional) -> `{"distance_m": 1234, "polyline": "...",
"provider": "OpenRouteService", "attribution": "..."}`

## Keys for the walking routes

The server asks the walking routes (the goal distance of the planned runs) to these services, in
this order, until one answers (the services with a key only if the key is set up). The key files are
ignored by git: never publish them. If no service answers, the app measures the planned routes in a
straight line and shows why.

1. **OpenRouteService** (free, OpenStreetMap data, 2000 routes per day, non-commercial use):
   file `server/ors_api_key.txt` (or environment variable `ORS_API_KEY`).
   Free key: sign up at https://account.heigit.org (HeiGIT, the institute behind openrouteservice),
   then create an API key of the free Standard plan in the dashboard.
2. **OSRM of FOSSGIS** (https://routing.openstreetmap.de, free, no key, fair use: at most 1 request
   per second): always used if OpenRouteService is not set up or does not answer.
3. **Google Routes API** (optional, needs billing enabled on the Google Cloud project):
   file `server/routes_api_key.txt` (or environment variable `ROUTES_API_KEY`).
   Google Cloud console > APIs & Services > Credentials > Create credentials > API key, with
   Application restrictions: None, API restrictions: Routes API. (The key of the app, restricted
   to the Android app, cannot be used: that restriction works for the Maps SDK, not for web services.)

## Run it on your PC (for the Android emulator)

From the `server` folder:

```
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

The emulator reaches the PC at `http://10.0.2.2:5000` (already the app default).
Check it in a browser on the PC: http://127.0.0.1:5000/api/health

To see what is in the database (users, posts, logins), from another terminal in the `server` folder:

```
.venv\Scripts\python show_db.py
```

To start again with an empty database: stop the server (Ctrl+C), delete `gymbro.db` and the
`uploads` folder, start the server again.

## Deploy it on PythonAnywhere

1. Create a free account on https://www.pythonanywhere.com
2. Open a **Bash console** and upload the code, e.g. `git clone <your repo url>`
   (or upload `app.py` and `requirements.txt` from the **Files** tab into `~/gymbro`).
3. In the console: `pip install --user -r <path to>/server/requirements.txt`
4. **Web** tab > **Add a new web app** > **Manual configuration** > Python 3.x.
5. In the **WSGI configuration file** replace everything with (adjust the path):
   ```python
   import sys
   sys.path.insert(0, "/home/<your username>/GymBro/server")
   from app import app as application
   ```
6. Click **Reload**, then open `https://<your username>.pythonanywhere.com/api/health`.
7. In the Android project, add to `local.properties`:
   `API_BASE_URL=https://<your username>.pythonanywhere.com/` and rebuild the app.
