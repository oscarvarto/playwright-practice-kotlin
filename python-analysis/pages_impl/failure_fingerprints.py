"""Failure Fingerprints page — recurring failures grouped by fingerprint.

Clusters failed executions by their ``failure_fingerprint`` hash and reports
occurrence counts, affected test counts, and a representative exception
message.  The page also offers a drill-down that joins back to the raw
``test_execution`` rows for a selected fingerprint.

The fingerprint hash is computed from ``exceptionClass|preferredFrame|message``
where ``preferredFrame`` uses the format ``ClassName#methodName`` (line numbers
are omitted so that fingerprints remain stable across refactors).

A nullable ``failure_embedding`` column exists in ``test_execution`` for future
vector-based similarity search (via ONNX Runtime embeddings and Turso's
``vector_distance_cos()``).  It is not queried by this page yet.

.. note::

   The database view ``v_failure_fingerprints`` uses correlated scalar
   subqueries, which ``pyturso`` does not support.  This module therefore
   queries raw tables and replicates the view logic in Polars.
"""

import polars as pl
import streamlit as st

from db import query_frame


def _build_fingerprint_frame() -> pl.DataFrame:
    """Query raw execution data and compute failure-fingerprint aggregations.

    The computation mirrors ``v_failure_fingerprints``:

    1. Select all executions that have a non-null ``failure_fingerprint``.
    2. Aggregate per fingerprint: occurrence count, affected test count,
       first/last seen timestamps.
    3. Pick the most recent exception class and message per fingerprint
       using ``sort().group_by().first()`` (replaces the correlated scalar
       subqueries in the original view).

    Returns
    -------
    polars.DataFrame
        Columns: ``failure_fingerprint``, ``occurrence_count``,
        ``affected_test_count``, ``first_seen_at``, ``last_seen_at``,
        ``last_exception_class``, ``sample_message``.
        Empty when no fingerprints exist.
    """
    raw = query_frame(
        """
        SELECT
            failure_fingerprint,
            test_case_id,
            exception_class,
            exception_message,
            COALESCE(finished_at_utc, created_at_utc) AS seen_at,
            execution_id
        FROM test_execution
        WHERE failure_fingerprint IS NOT NULL
        ORDER BY failure_fingerprint, seen_at DESC, execution_id DESC
        """
    )

    if raw.is_empty():
        return pl.DataFrame()

    # Aggregated statistics per fingerprint
    agg = raw.group_by("failure_fingerprint").agg(
        pl.len().alias("occurrence_count"),
        pl.col("test_case_id").n_unique().alias("affected_test_count"),
        pl.col("seen_at").min().alias("first_seen_at"),
        pl.col("seen_at").max().alias("last_seen_at"),
    )

    # Most recent exception details per fingerprint (ROW_NUMBER equivalent)
    latest = (
        raw.sort("seen_at", "execution_id", descending=[True, True])
        .group_by("failure_fingerprint")
        .first()
        .select(
            "failure_fingerprint",
            pl.col("exception_class").alias("last_exception_class"),
            pl.col("exception_message").alias("sample_message"),
        )
    )

    result = agg.join(latest, on="failure_fingerprint", how="left").sort(
        "occurrence_count",
        "affected_test_count",
        "last_seen_at",
        descending=[True, True, True],
    )

    return result


def render() -> None:
    """Render the Failure Fingerprints page.

    Shows a summary table of all known fingerprints ranked by occurrence
    count.  A selectbox allows the user to pick one fingerprint and
    view every matching execution with its exception details and run
    metadata.
    """
    st.header("Failure Fingerprints")

    fps = _build_fingerprint_frame()

    if fps.is_empty():
        st.info("No failure fingerprints recorded.")
        return

    st.dataframe(fps, width="stretch", hide_index=True)

    # Drill-down: select a fingerprint and show matching executions
    fp_options = fps["failure_fingerprint"].to_list()
    selected_fp = st.selectbox("Drill into fingerprint", fp_options)

    if selected_fp:
        matching = query_frame(
            """
            SELECT
                te.execution_id,
                te.finished_at_utc,
                te.exception_class,
                te.exception_message,
                tc.class_name,
                tc.method_name,
                tr.run_id,
                tr.trigger_source
            FROM test_execution te
            JOIN test_case tc ON tc.test_case_id = te.test_case_id
            JOIN test_run tr ON tr.run_id = te.run_id
            WHERE te.failure_fingerprint = ?
            ORDER BY te.finished_at_utc DESC
            """,
            (selected_fp,),
        )
        st.subheader(f"Executions for fingerprint: {selected_fp[:16]}...")
        st.dataframe(matching, width="stretch", hide_index=True)
