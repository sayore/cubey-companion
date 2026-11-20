#!/usr/bin/env python3
"""Call the real Google API through QB2's production Java provider using a local .env."""
import os
import subprocess
from pathlib import Path
from live_configuration import LiveConfigurationError, load_live_environment

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    try:
        values = load_live_environment(ROOT / ".env")
    except LiveConfigurationError as error:
        # Parser errors contain only fixed diagnostics/line numbers, never values.
        print(str(error))
        return 2
    except (OSError, UnicodeError):
        print("Local .env could not be read.")
        return 2
    env = {**os.environ, **values}
    result = subprocess.run(["./gradlew", "--no-daemon", "liveApiCheck"], cwd=ROOT, env=env)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
