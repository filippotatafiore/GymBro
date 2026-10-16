# GymBro REST API

Flask server that stores the app users (registration / login, body data, profile pictures) and
the posts they publish in **Cloud Firestore**, the database of Firebase. It also computes the
walking routes of the runs with OpenRouteService or the Google Routes API, and answers the chat with
the AI coach with the language models of Groq or Google Gemini (their keys stay on the server).

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
| PUT    | `/api/me/appearance`          | yes  | Favorite color and theme (see below) |
| PUT    | `/api/me/account`             | yes  | Username, email, password (see below)|
| POST   | `/api/me/delete-account`      | yes  | Deletes the account and all its data |
| PUT    | `/api/me/active-plan`         | yes  | `{"plan_id": 3}` or null             |
| GET    | `/api/me/sessions`            | yes  | Finished sessions, newest first      |
| POST   | `/api/me/sessions`            | yes  | Save a finished session (see below)  |
| DELETE | `/api/me/sessions/<id>`       | yes  | Delete one of your sessions          |
| PUT    | `/api/me/picture`             | yes  | Body: JPEG image (max 900 KB)        |
| POST   | `/api/me/posts`               | yes  | Publish a post (see below)           |
| DELETE | `/api/me/posts/<id>`          | yes  | Delete one of your posts             |
| POST   | `/api/routes/walking`         | yes  | Walking route (see below)            |
| POST   | `/api/chat`                   | yes  | Answer of the AI coach (see below)   |
| GET    | `/api/me/plans`               | yes  | Your workout plans                   |
| POST   | `/api/me/plans`               | yes  | New plan written by the coach        |
| GET    | `/api/me/plans/<id>`          | yes  | One of your plans                    |
| PUT    | `/api/me/plans/<id>`          | yes  | Save your changes to a plan          |
| DELETE | `/api/me/plans/<id>`          | yes  | Delete one of your plans             |
| PUT    | `/api/me/plans/<id>/public`   | yes  | `{"public": true}`: on your profile  |
| GET    | `/api/users/<id>`             |      | Public profile, with counts (below)  |
| GET    | `/api/users/<id>/picture`     |      | Profile picture (404 if none)        |
| GET    | `/api/users/<id>/posts`       |      | Posts published by the user          |
| GET    | `/api/users/<id>/plans`       |      | Public workout plans of the user     |
| GET    | `/api/users/<id>/plans/<pid>` |      | One public plan of the user          |
| POST   | `/api/users/<id>/plans/<pid>/copy` | yes | Add a copy of it to your plans  |
| GET    | `/api/users/search?q=...`     | yes  | Users whose username contains q      |
| POST   | `/api/users/<id>/follow`      | yes  | Follow the user                      |
| DELETE | `/api/users/<id>/follow`      | yes  | Stop following the user              |
| GET    | `/api/users/<id>/followers`   | yes  | Users following the user             |
| GET    | `/api/users/<id>/following`   | yes  | Users followed by the user           |
| GET    | `/api/feed`                   | yes  | Posts of the users you follow        |
| GET    | `/api/me/conversations`       | yes  | Users you can share plans with       |
| GET    | `/api/me/conversations/<id>`  | yes  | Plans shared with that user          |
| POST   | `/api/me/conversations/<id>`  | yes  | Share a plan: `{"plan_id": 3}`       |
| POST   | `/api/me/conversations/<id>/read` | yes | The plans received are seen      |
| GET    | `/api/me/shares/<id>`         | yes  | A plan shared with you or by you     |
| POST   | `/api/me/shares/<id>/save`    | yes  | Add a plan received to your plans    |

"Auth" endpoints need the header `Authorization: Bearer <token>` (the token is returned by
register/login).

Body of `POST /api/me/posts`, depending on the kind of post:

- manual result: `{"kind": "manual", "title": "Deadlift", "metric": "weight" | "time",
  "better": "higher" | "lower", "amount": 150, "notes": "optional"}` (amount: kg or seconds)
- run session: `{"kind": "run", "duration_s": 3600, "distance_km": 10.4, "session_start": "2026-10-12T08:00:00Z",
  "kcal": 780, "max_speed_kmh": 14.2, "goal_km": 10, "route": ["_p~iF~ps|U_ulLnnqC"], "steps": 9850}`
  (kcal, max_speed_kmh, goal_km, route and steps are optional; route = the lines followed, as
  [Google encoded polylines](https://developers.google.com/maps/documentation/utilities/polylinealgorithm);
  steps = counted by the step sensor of the phone)
- gym session: `{"kind": "gym", "duration_s": 2700, "session_start": "...", "exercises":
  [{"name": "Bench Press", "muscle_group": "Chest", "sets": 4, "reps": 8, "weight_kg": 60}]}`
- swim session: `{"kind": "swim", "duration_s": 1800, "session_start": "...", "pool_length_m": 25,
  "laps": 60, "goal_laps": 60}` (goal_laps is optional; the server adds `distance_km` = laps x
  pool length, here 1.5)

Body of `POST /api/me/sessions` (LAST SESSIONS and calendar of the app): `{"client_id": "uuid",
"type": "gym" | "run" | "swim", "start_ms": 1791830919733, "duration_ms": 2700000, ...}`; run:
`distance_km`, `kcal`, `max_speed_kmh`, `goal_km`, `route`, `steps` (as in the posts); swim:
`pool_length_m`, `laps`, `goal_laps` (as in the posts); gym: `exercises` (as done in the
session: `name`, `muscle_group`, `sets`, `reps` as a text, `weight_kg`, `rest_s`, `notes`, `done`),
`plan_id`, `plan_name`, `plan_day`, `plan_day_name`. The same `client_id` is saved only once (the
app can send a session again after an error). A post published from a session has `"session_id"`
in its body: the session is then marked as published (`published_post_id`).

Body of `PUT /api/me/account`: `{"current_password": "...", "username": "...", "email": "...",
"new_password": "..."}` (only the current password is required). After a new password the other
devices are logged out. `POST /api/me/delete-account` with `{"password": "..."}` deletes the user,
the logins, the posts, the sessions, the plans and the profile picture.

Body of `PUT /api/me/profile` (every value optional, null = not set):
`{"height_cm": 180, "weight_kg": 72.5, "birth_year": 2001, "sex": "male" | "female" | "other"}`

Body of `PUT /api/me/appearance` (both required): `{"accent": "blue" | "violet" | "pink" | "red" |
"orange" | "amber" | "green" | "teal", "theme": "dark" | "light"}`. The look of the app is saved in
the account (the same on every device): `accent` and `theme` are in the logged user (`/api/me`,
login, register); a new account starts with `"blue"` and `"dark"`.

Body of `POST /api/routes/walking`: `{"origin": {"lat": 45.46, "lng": 9.19}, "stop": null,
"destination": {"lat": 45.47, "lng": 9.18}}` (stop optional) -> `{"distance_m": 1234, "polyline": "...",
"provider": "OpenRouteService", "attribution": "..."}`

Body of `POST /api/chat`: the whole conversation, oldest message first, the last one of the user:
`{"messages": [{"role": "user", "content": "How do I do a squat?"}, {"role": "assistant", "content": "..."},
{"role": "user", "content": "And the deadlift?"}]}` -> `{"reply": "...", "model": "openai/gpt-oss-120b",
"provider": "Groq"}`. 429 if all the models have used up their free limits.
With `"plan_id": 3` in the body: chat about that workout plan (see below); the answer also has
`"plan"`: the plan changed by the coach (already saved), or `null`.

Body of `POST /api/me/plans`: `{"goal": "lose_weight" | "build_muscle" | "gain_mass" | "strength" |
"fitness", "level": "beginner" | "intermediate" | "advanced", "days_per_week": 3,
"minutes_per_session": 60, "preferences": "no squat, more free weights", "language": "Italian"}`
-> the plan: `{"id", "name", "notes", "days": [{"name": "Day 1 · Push", "exercises": [{"name":
"Bench Press", "muscle_group": "Chest", "sets": 4, "reps": "8-10", "rest_s": 90, "notes": ""}]}],
"goal", "level", "days_per_week", "minutes_per_session", "preferences", "created_at", "updated_at"}`.
Body of `PUT /api/me/plans/<id>`: `{"name", "notes", "days"}` as above (`rest_s` can be null).
Every plan also has `"public"` (true: everyone sees it on your profile, `PUT /api/me/plans/<id>/public`)
and `"from_username"` (the plan is a copy of a plan of that user, or null).

### Social (social page of the app)

- `GET /api/users/<id>` also has `posts_count`, `followers_count`, `following_count` and, with the
  login (of another user), `is_following` (you follow them) and `follows_you`.
- The users in the lists (search, followers, following) are `{"id", "username", "has_picture",
  "is_following", "follows_you"}`. The search ignores upper/lower case: first the usernames that
  start with `q`, then those that contain it (at most 30).
- `GET /api/feed` -> `{"posts": [...], "next": "..."}`: the posts of the users you follow, newest
  first, 20 at a time; for the next page `GET /api/feed?before=<next>` (`next` is null on the last
  page). Every post also has `"user"` and `"personal_best"`: true if it is one of the personal bests
  of that user (the same rules of the profile of the app: the fastest run of every distance, the
  swim with the best average pace of every distance, the best manual result of every exercise).
- Public plans: everyone can see them on the profile of the user; `.../copy` adds a copy to your
  plans (then it is a plan like the others, with `from_username`).
- Shared plans ("Messages" of the app): only between users that follow each other. The receiver
  gets a copy of the plan as it is when it is shared (the later changes are not sent) and can add it
  to their plans (`/api/me/shares/<id>/save`, the plan added before if called again).
  `GET /api/me/conversations` -> `[{"user", "can_share", "unread", "last": {"plan_name", "from_me",
  "created_at"} or null}]`; `GET /api/me/conversations/<id>` -> `{"user", "can_share", "shares":
  [{"id", "from_me", "plan", "read", "saved_plan_id", "created_at"}]}` (oldest first).
- `POST /api/me/delete-account` also deletes the follows of the user (in both directions) and the
  plans they shared or received.

## Data in Firestore

The collections (the "tables" of Firestore) are described at the top of `app.py`: `users`, `posts`,
`sessions`, `plans`, `follows`, `shares`, `tokens`, `pictures`, plus `usernames` and `emails` (they keep usernames and emails unique) and
`counters` (the ids 1, 2, 3... of users, posts, plans, sessions and shares). No index has to be
created by hand: the queries of the server use only the indexes that Firestore creates by itself. To see the data: Firebase console >
your project > **Firestore Database** > **Data**.

## Keys (never in git)

| What | On Vercel (Settings > Environment Variables) | On your PC (files in this folder) |
|------|----------------------------------------------|-----------------------------------|
| Firebase service account (database) | `FIREBASE_SERVICE_ACCOUNT` = the whole content of the JSON file | `firebase_key.json` |
| OpenRouteService (walking routes) | `ORS_API_KEY` | `ors_api_key.txt` |
| Google Routes API (optional, needs billing) | `ROUTES_API_KEY` | `routes_api_key.txt` |
| Groq (AI coach) | `GROQ_API_KEY` | `groq_api_key.txt` |
| Google Gemini (optional, AI coach when the Groq models are busy) | `GEMINI_API_KEY` | `gemini_api_key.txt` |

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

## AI coach (chat page of the app)

The app sends the whole conversation (saved only on the phone) to `POST /api/chat`; the server adds
the instructions of the coach (`COACH_INSTRUCTIONS` in `app.py`) and asks the answer to these
models, in this order, until one answers:

1. **Groq** (free, no credit card): `openai/gpt-oss-120b`, `qwen/qwen3.8-27b`, `openai/gpt-oss-20b`.
   Each model has its own free limits (about 1000 requests and 200,000 tokens per day, 8000 tokens
   per minute): when one has used them up (error 429) the next one answers, with the same
   conversation. Key: https://console.groq.com > **API Keys** > **Create API Key**.
2. **Google Gemini** (optional, free): `gemini-3.8-flash`, `gemini-3.5-flash-lite`, used only if all
   the Groq models are busy. Key: https://aistudio.google.com > **Get API key**. On the free plan
   Google may use the messages to improve its products.

Only the newest messages that fit in about 3000 tokens are sent to the model (`CHAT_HISTORY_CHARS`),
so the free tokens per minute are enough. The models change over time (e.g. Groq turned off the
Llama models in August 2026): to use other models, set the environment variables `GROQ_MODELS` /
`GEMINI_MODELS` on Vercel (names separated by commas, as in https://console.groq.com/docs/models),
without changing the code. The errors of the models are in Vercel > project > **Logs**.

### Workout plans

The coach writes a gym plan (`POST /api/me/plans`) from the goal, level, days, minutes and
preferences chosen in the app and from the body data of the profile (height, weight, BMI, age,
sex: never the username, the email or the password). The models answer with a JSON object that must
follow a schema (`PLAN_SCHEMA` in `app.py`, "structured outputs" of Groq); an answer that is not a
valid plan is discarded and the next model is asked. The plan is saved in Firestore (`plans`).
In the chat about a plan (`/api/chat` with `plan_id`) the coach sees the plan as it is in the
database, also after the changes made by hand in the app, and answers with a reply and, if asked,
the whole changed plan, which is saved. A plan is a long answer: up to 75 seconds are allowed
(Vercel Hobby with Fluid compute, the default, allows 300 seconds).

## Deploy on Vercel

1. Push the project to GitHub.
2. On https://vercel.com: sign up with GitHub (Hobby plan), then **Add New... > Project** and
   import the GymBro repository.
3. **Root Directory**: `server`. The Framework Preset becomes **Flask** (`app.py` defines `app`).
4. **Environment Variables**: `FIREBASE_SERVICE_ACCOUNT`, `ORS_API_KEY` and `GROQ_API_KEY`
   (optional: `GEMINI_API_KEY`), see above.
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
