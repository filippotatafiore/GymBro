# GymBro REST API

Flask server that stores the app users (registration / login) and the results they publish,
in a SQLite database (`gymbro.db`, created automatically on first start).

| Method | Path                          | Auth | Description                          |
|--------|-------------------------------|------|--------------------------------------|
| GET    | `/api/health`                 |      | Check that the server is running     |
| POST   | `/api/register`               |      | `{username, email, password}`        |
| POST   | `/api/login`                  |      | `{email, password}`                  |
| POST   | `/api/logout`                 | yes  | Invalidates the token                |
| GET    | `/api/me`                     | yes  | Logged user                          |
| PUT    | `/api/me/picture`             | yes  | Body: JPEG image                     |
| POST   | `/api/me/results`             | yes  | `{title, value}`                     |
| GET    | `/api/users/<id>`             |      | Public profile                       |
| GET    | `/api/users/<id>/picture`     |      | Profile picture (404 if none)        |
| GET    | `/api/users/<id>/results`     |      | Results published by the user        |

"Auth" endpoints need the header `Authorization: Bearer <token>` (the token is returned by
register/login).

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

To see what is in the database (users, results, logins), from another terminal in the `server` folder:

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
