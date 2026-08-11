"""Parser service package."""

import sys
from pathlib import Path

# Keep repository-local generated contracts importable until the shared helper
# is corrected to add the generated package root itself.
_generated = Path(__file__).resolve().parents[3] / "_generated"
if _generated.is_dir() and str(_generated) not in sys.path:
    sys.path.insert(0, str(_generated))
