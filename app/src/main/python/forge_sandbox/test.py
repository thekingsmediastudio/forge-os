"""Validation script for Chaquopy integration."""

import sys
import numpy as np


def validate():
    """Return system info to verify Chaquopy works."""
    return {
        "python_version": sys.version,
        "numpy_version": np.__version__,
        "platform": sys.platform,
        "status": "Chaquopy is working!"
    }
