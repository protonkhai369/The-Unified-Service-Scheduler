#!/usr/bin/env python3
"""Creates or updates the demo users of the Unified Service Scheduler in Supabase Auth.

One user per role, with the role and its link in app_metadata (which only the Admin API or the
dashboard can change, never the user):

    scheduler.admin@example.com            admin
    scheduler.advisor.saigon@example.com   advisor  dealership_id = SAIGON_DEALERSHIP_ID (default 2)
    emma.wilson@example.com                customer customer_id   = EMMA_CUSTOMER_ID     (default 1)
    minh.duc@example.com                   customer customer_id   = DUC_CUSTOMER_ID      (default 2)

The defaults are the ids of the demo data (db/demo/R__demo_data.sql) in an empty database; check them
with GET /api/v1/dealerships if yours differ. Emails are confirmed directly, so nothing is sent.

Usage:  DEMO_USER_PASSWORD='...' python scripts/supabase-demo-users.py
Reads:  SUPABASE_URL and SUPABASE_SECRET_KEY from the environment or from .env in the project root;
        DEMO_USER_PASSWORD (at least 8 characters) from the environment only, never from a file.
Needs:  Python 3.9+, standard library only.

Re-running is safe: existing users get the password and app_metadata again. Neither the secret key nor
the password is ever printed.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

PER_PAGE = 200


def main() -> int:
    settings = read_dotenv(Path(__file__).resolve().parent.parent / ".env")
    url = setting("SUPABASE_URL", settings).rstrip("/")
    secret_key = setting("SUPABASE_SECRET_KEY", settings)
    password = os.environ.get("DEMO_USER_PASSWORD", "")
    if not url or not secret_key:
        return fail("SUPABASE_URL and SUPABASE_SECRET_KEY must be set, in .env or in the environment.")
    if len(password) < 8:
        return fail("Set DEMO_USER_PASSWORD (at least 8 characters) in the environment.")

    users = [
        ("scheduler.admin@example.com", {"scheduler_role": "admin"}),
        ("scheduler.advisor.saigon@example.com",
         {"scheduler_role": "advisor", "dealership_id": int(os.environ.get("SAIGON_DEALERSHIP_ID", "2"))}),
        ("emma.wilson@example.com",
         {"scheduler_role": "customer", "customer_id": int(os.environ.get("EMMA_CUSTOMER_ID", "1"))}),
        ("minh.duc@example.com",
         {"scheduler_role": "customer", "customer_id": int(os.environ.get("DUC_CUSTOMER_ID", "2"))}),
    ]

    admin = AdminApi(url, secret_key)
    existing = admin.user_ids_by_email()
    for email, link in users:
        # Every scheduler key, so a re-run also removes a link the role no longer has (null deletes it).
        app_metadata = {"scheduler_role": None, "customer_id": None, "dealership_id": None, **link}
        user_id = existing.get(email)
        if user_id:
            admin.request("PUT", f"/admin/users/{user_id}",
                          {"password": password, "email_confirm": True, "app_metadata": app_metadata})
            action = "updated"
        else:
            created = admin.request("POST", "/admin/users",
                                    {"email": email, "password": password, "email_confirm": True,
                                     "app_metadata": app_metadata})
            user_id = created["id"]
            action = "created"
        print(f"{action:7} {email:38} {describe(link)}  user {user_id}")
    return 0


class AdminApi:
    """The Supabase Auth Admin API, authorised with the project's secret key."""

    def __init__(self, url: str, secret_key: str):
        self.base = f"{url}/auth/v1"
        self.headers = {"apikey": secret_key, "Authorization": f"Bearer {secret_key}",
                        "Content-Type": "application/json", "Accept": "application/json"}

    def user_ids_by_email(self) -> dict:
        ids, page = {}, 1
        while True:
            users = self.request("GET", f"/admin/users?page={page}&per_page={PER_PAGE}").get("users", [])
            ids.update({user["email"].lower(): user["id"] for user in users if user.get("email")})
            if len(users) < PER_PAGE:
                return ids
            page += 1

    def request(self, method: str, path: str, body: dict | None = None) -> dict:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=self.headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            # Supabase's error message names the problem (e.g. a weak password), never the key.
            reason = error.read().decode(errors="replace")[:300]
            raise SystemExit(f"{method} {path.split('?')[0]} failed with HTTP {error.code}: {reason}") from None
        except urllib.error.URLError as error:
            raise SystemExit(f"Cannot reach {self.base}: {error.reason}") from None


def describe(link: dict) -> str:
    extra = ", ".join(f"{key}={value}" for key, value in link.items() if key != "scheduler_role")
    return f"{link['scheduler_role']:8} {extra:18}"


def read_dotenv(path: Path) -> dict:
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def setting(name: str, dotenv: dict) -> str:
    return os.environ.get(name) or dotenv.get(name, "")


def fail(message: str) -> int:
    print(message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
