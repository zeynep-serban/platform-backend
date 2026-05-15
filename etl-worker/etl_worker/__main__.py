"""``python -m etl_worker`` entry point — thin wrapper over :func:`cli.main`.

The console script defined in ``pyproject.toml`` ``[project.scripts]``
calls :func:`etl_worker.cli.main` directly, so this module exists only
to make ``python -m etl_worker …`` behave identically.
"""

from __future__ import annotations

from .cli import main

raise SystemExit(main())
