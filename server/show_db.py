"""
Prints the content of the GymBro database (users, published results, login tokens).

From the server folder:
    .venv\\Scripts\\python show_db.py
"""

import os
import sqlite3

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("GYMBRO_DB", os.path.join(BASE_DIR, "gymbro.db"))


def print_table(db, title, query, columns):
    rows = db.execute(query).fetchall()
    print(f"\n{title} ({len(rows)})")
    if not rows:
        print("  (empty)")
        return

    # column width = longest value of the column
    table = [columns] + [[str(value) for value in row] for row in rows]
    widths = [max(len(row[i]) for row in table) for i in range(len(columns))]
    for row in table:
        print("  " + "  ".join(value.ljust(width) for value, width in zip(row, widths)))


def main():
    if not os.path.exists(DB_PATH):
        print(f"Database not created yet ({DB_PATH}).")
        print("Start the server once (python app.py): it creates the empty tables.")
        return

    print(f"Database: {DB_PATH}")
    with sqlite3.connect(DB_PATH) as db:
        print_table(
            db, "USERS",
            "SELECT id, username, email, CASE has_picture WHEN 1 THEN 'yes' ELSE 'no' END, created_at, "
            "substr(password_hash, 1, 30) || '...' "
            "FROM users ORDER BY id",
            ["id", "username", "email", "picture", "registered (UTC)", "password (only its hash is stored)"],
        )
        print_table(
            db, "POSTS",
            "SELECT posts.id, users.username, posts.kind, posts.title, "
            # short summary of the post, depending on its kind
            "CASE posts.kind "
            "  WHEN 'manual' THEN posts.metric || ' ' || posts.amount || "
            "       CASE posts.metric WHEN 'time' THEN ' s' ELSE ' kg' END || ', ' || posts.better || ' is better' "
            "  WHEN 'run' THEN posts.distance_km || ' km in ' || posts.duration_s || ' s' || "
            "       COALESCE(', ' || round(posts.kcal) || ' kcal', '') || "
            "       COALESCE(', goal ' || posts.goal_km || ' km', '') || "
            "       CASE WHEN posts.route IS NULL THEN '' ELSE ', route saved' END "
            "  ELSE json_array_length(posts.exercises) || ' exercises in ' || posts.duration_s || ' s' "
            "END, "
            "posts.created_at "
            "FROM posts JOIN users ON users.id = posts.user_id ORDER BY posts.id",
            ["id", "user", "kind", "title", "details", "published (UTC)"],
        )
        print_table(
            db, "LOGIN TOKENS (one per logged-in device)",
            "SELECT users.username, tokens.created_at "
            "FROM tokens JOIN users ON users.id = tokens.user_id ORDER BY tokens.created_at",
            ["user", "logged in (UTC)"],
        )


if __name__ == "__main__":
    main()
