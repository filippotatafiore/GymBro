# GymBro REST API

Flask server that stores the app users (registration / login, body data, profile pictures) and
the posts they publish in **Cloud Firestore**, the database of Firebase. It also computes the
walking routes of the runs with OpenRouteService or the Google Routes API (their keys stay on the
server).

It runs online for free, always reachable, without your PC:

- **Server**: [Vercel](https://vercel.com) (Hobby plan, free, no credit card). Every push to
  GitHub updates it.
- **Database**: Firebase, Cloud Firestore (Spark plan, free, no credit card).

(Firebase could also run the server, with Cloud Functions, but they need the Blaze plan, with a
billing account linked to the project.)

| Method | Path                          | Auth | Description                          |
|--------|-------------------------------|------|--------------------------------------|
| GET    | `/api/health`                 |      | Server running and database reachable|
| POST   | `/api/register`               |      | `{username, email, password}`        |
| POST   | `/api/login`                  |      | `{email, password}`                  |
| POST   | `/api/logout`                 | yes  | Invalidates the token                |
| GET    | `/api/me`                     | yes  | Logged user (with email, body data)  |
| PUT    | `/api/me/profile`             | yes  | Body data (see below)                |
| PUT    | `/api/me/picture`             | yes  | Body: JPEG image (max 900 KB)        |
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

## Data in Firestore

The collections (the "tables" of Firestore) are described at the top of `app.py`: `users`, `posts`,
`tokens`, `pictures`, plus `usernames` and `emails` (they keep usernames and emails unique) and
`counters` (the ids 1, 2, 3... of users and posts). To see the data: Firebase console >
your project > **Firestore Database** > **Data**.

## Keys (never in git)

| What | On Vercel (Settings > Environment Variables) | On your PC (files in this folder) |
|------|----------------------------------------------|-----------------------------------|
| Firebase service account (database) | `FIREBASE_SERVICE_ACCOUNT` = the whole content of the JSON file | `firebase_key.json` |
| OpenRouteService (walking routes) | `ORS_API_KEY` | `ors_api_key.txt` |
| Google Routes API (optional, needs billing) | `ROUTES_API_KEY` | `routes_api_key.txt` |

The files are ignored by git. The Firebase key gives full access to the database: never publish it.

Firebase key: Firebase console > ⚙ **Project settings** > **Service accounts** >
**Generate new private key**.

Walking routes: the server asks them to these services, in this order, until one answers (the
services with a key only if the key is set up). If no service answers, the app measures the planned
routes in a straight line and shows why.

1. **OpenRouteService** (free, OpenStreetMap data, 2000 routes per day, non-commercial use).
   Free key: sign up at https://account.heigit.org, then create an API key of the free Standard plan.
2. **OSRM of FOSSGIS** (https://routing.openstreetmap.de, free, no key, fair use: at most 1 request
   per second): used if OpenRouteService is not set up or does not answer.
3. **Google Routes API** (optional, needs billing enabled on the Google Cloud project). Key with
   Application restrictions: None, API restrictions: Routes API.

## Deploy on Vercel

1. Push the project to GitHub.
2. On https://vercel.com: sign up with GitHub (Hobby plan), then **Add New... > Project** and
   import the GymBro repository.
3. **Root Directory**: `server`. The Framework Preset becomes **Flask** (`app.py` defines `app`).
4. **Environment Variables**: `FIREBASE_SERVICE_ACCOUNT` and `ORS_API_KEY` (see above).
5. **Deploy**. Then open `https://<project>.vercel.app/api/health`: it must answer
   `{"database": "ok", "status": "ok"}`.
6. In the Android project, add to `local.properties`: `API_BASE_URL=https://<project>.vercel.app/`
   and run the app again.

`vercel.json` runs the server in Frankfurt (`fra1`), close to the database (create it in Europe).
Use the production domain shown in **Domains** (`<project>.vercel.app`): the other URLs of the
single deployments ask for a Vercel login.

## Run it on your PC (optional, to try changes before pushing them)

From the `server` folder, with `firebase_key.json` in it (it uses the same online database):

```
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

The emulator reaches the PC at `http://10.0.2.2:5000`: remove `API_BASE_URL` from
`local.properties` (it is the default) and run the app again.

## Copy the old SQLite database (once)

`migrate_to_firestore.py` copies `gymbro.db` and the `uploads` folder of the old server into
Firestore, with the same ids (users, passwords, logins, posts, pictures). With `firebase_key.json`
in this folder and the virtual environment above:

```
.venv\Scripts\python migrate_to_firestore.py
```

Then `gymbro.db`, `uploads` and `migrate_to_firestore.py` can be deleted.
