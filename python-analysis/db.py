"""Database helper for querying the test-statistics Turso database.

This module provides a thin convenience layer over ``pyturso`` that returns
query results as :class:`polars.DataFrame` instances.  The database path is
resolved once per call with the following precedence:

1. Streamlit secrets (``[db] path = "..."`` in ``.streamlit/secrets.toml``).
2. A default path relative to this file that points to
   ``src/test/resources/test-statistics.db`` in the parent Gradle project.
"""

from pathlib import Path

import polars as pl
import turso

_DEFAULT_DB_PATH: Path = Path(__file__).resolve().parent.parent / "src" / "test" / "resources" / "test-statistics.db"
"""Fallback database path, relative to the repository root."""


def _db_path() -> str:
    """Resolve the database file path.

    Returns the path configured in Streamlit secrets when available,
    falling back to :data:`_DEFAULT_DB_PATH` otherwise.  Any exception
    during secrets lookup (missing file, missing key, import error) is
    silently caught so that the module works outside Streamlit as well.
    """
    try:
        import streamlit as st

        return st.secrets["db"]["path"]
    except Exception:
        return str(_DEFAULT_DB_PATH)


def query_frame(sql: str, params: tuple = ()) -> pl.DataFrame:
    """Execute a read-only SQL query and return the result as a DataFrame.

    Parameters
    ----------
    sql:
        A SQL ``SELECT`` statement (or any statement that produces a
        result set).
    params:
        Positional bind parameters passed to ``cursor.execute()``.

    Returns
    -------
    polars.DataFrame
        A DataFrame whose columns match the query's result columns.
        Returns an empty DataFrame with no columns when the query
        produces no rows.

    Raises
    ------
    turso.lib.DatabaseError
        If the database file is locked or inaccessible.
    """
    conn = turso.connect(_db_path())
    try:
        cur = conn.cursor()
        cur.execute(sql, params)
        columns = [column[0] for column in cur.description]
        rows = cur.fetchall()
        return pl.DataFrame([dict(zip(columns, row, strict=False)) for row in rows])
    finally:
        conn.close()
