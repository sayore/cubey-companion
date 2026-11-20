"""Explicit local test configuration; never executes or expands dotenv values."""
from pathlib import Path

class LiveConfigurationError(ValueError):
    pass


ALLOWED = {"GOOGLE_API_KEY", "QB2_MODEL", "QB2_API_ENDPOINT"}


def load_live_environment(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise LiveConfigurationError("Local .env missing; copy .env.example first.")
    if path.stat().st_size > 16384:
        raise LiveConfigurationError("Local .env is too large.")
    values = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, separator, value = line.partition("=")
        name, value = name.strip(), value.strip()
        if not separator or name not in ALLOWED or name in values:
            raise LiveConfigurationError(f"Invalid or duplicate setting at .env line {number}.")
        if value.startswith(('"', "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise LiveConfigurationError(f"Unclosed quote at .env line {number}.")
            value = value[1:-1]
        if any(ord(char) < 32 or ord(char) == 127 for char in value):
            raise LiveConfigurationError(f"Invalid control character at .env line {number}.")
        values[name] = value
    if not values.get("GOOGLE_API_KEY", "").strip():
        raise LiveConfigurationError("GOOGLE_API_KEY is empty in the local .env. No API request sent.")
    return values
