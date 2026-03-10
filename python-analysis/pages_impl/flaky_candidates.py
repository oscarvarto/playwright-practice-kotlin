"""Flaky Candidates page — tests with both pass and fail history.

Identifies test cases that have recorded at least one ``PASSED`` *and* at
least one ``FAILED`` execution.  Status *flips* — consecutive executions
where the outcome changed — are counted in Polars as a proxy for
instability.

.. note::

   The database view ``v_flaky_candidates`` uses a CTE with a correlated
   subquery, which ``pyturso`` does not support.  This module therefore
   queries raw tables and replicates the view logic in Polars.
"""

import plotly.express as px
import polars as pl
import streamlit as st

from db import query_frame


def _build_flaky_frame() -> pl.DataFrame:
    """Query raw execution data and compute flaky-candidate metrics.

    The computation mirrors ``v_flaky_candidates``:

    1. Fetch every execution's status ordered chronologically per test case.
    2. Use :meth:`polars.Expr.shift` to obtain the previous status within
       each test-case partition (replaces the correlated subquery in the view).
    3. Count passes, failures, and status flips per test case.
    4. Determine the latest status per test case.
    5. Filter to test cases that have *both* passes and failures.

    Returns
    -------
    polars.DataFrame
        Columns: ``class_name``, ``method_name``, ``pass_count``,
        ``fail_count``, ``flip_count``, ``latest_status``,
        ``historical_fail_rate``.  Empty when no flaky candidates exist.
    """
    raw = query_frame(
        """
        SELECT
            te.test_case_id,
            te.execution_id,
            te.canonical_status,
            te.attempt_index,
            COALESCE(te.started_at_utc, te.created_at_utc) AS sort_ts,
            tc.class_name,
            tc.method_name
        FROM test_execution te
        JOIN test_case tc ON tc.test_case_id = te.test_case_id
        ORDER BY te.test_case_id, sort_ts, te.attempt_index, te.execution_id
        """
    )

    if raw.is_empty():
        return pl.DataFrame()

    # Previous status per test case (LAG equivalent)
    with_prev = raw.with_columns(pl.col("canonical_status").shift(1).over("test_case_id").alias("previous_status"))

    # Aggregate per test case
    agg = (
        with_prev.group_by("test_case_id", "class_name", "method_name")
        .agg(
            (pl.col("canonical_status") == "PASSED").sum().alias("pass_count"),
            (pl.col("canonical_status") == "FAILED").sum().alias("fail_count"),
            (pl.col("previous_status").is_not_null() & (pl.col("previous_status") != pl.col("canonical_status")))
            .sum()
            .alias("flip_count"),
        )
        .filter((pl.col("pass_count") > 0) & (pl.col("fail_count") > 0))
    )

    if agg.is_empty():
        return pl.DataFrame()

    # Latest status: last row per test case in chronological order
    latest = (
        raw.sort("sort_ts", "attempt_index", "execution_id")
        .group_by("test_case_id")
        .last()
        .select("test_case_id", pl.col("canonical_status").alias("latest_status"))
    )

    result = agg.join(latest, on="test_case_id", how="left")

    result = (
        result.with_columns(
            (pl.col("fail_count").cast(pl.Float64) / (pl.col("pass_count") + pl.col("fail_count"))).alias(
                "historical_fail_rate"
            )
        )
        .select(
            "class_name",
            "method_name",
            "pass_count",
            "fail_count",
            "flip_count",
            "latest_status",
            "historical_fail_rate",
        )
        .sort("flip_count", "fail_count", descending=[True, True])
    )

    return result


def render() -> None:
    """Render the Flaky Candidates page.

    Displays a data table augmented with a computed ``historical_fail_rate``
    column and an interactive scatter plot of flip count versus fail rate,
    colored by the latest observed status.
    """
    st.header("Flaky Candidates")

    flaky = _build_flaky_frame()

    if flaky.is_empty():
        st.info("No flaky candidates detected (all tests have uniform outcomes).")
        return

    st.dataframe(flaky, width="stretch", hide_index=True)

    # Scatter: flip_count vs fail_rate
    fig = px.scatter(
        flaky.to_pandas(),
        x="historical_fail_rate",
        y="flip_count",
        hover_data=["class_name", "method_name"],
        color="latest_status",
        title="Flakiness: flip count vs historical fail rate",
        color_discrete_map={
            "PASSED": "#22c55e",
            "FAILED": "#ef4444",
            "ABORTED": "#f59e0b",
        },
    )
    fig.update_layout(xaxis_title="Historical fail rate", yaxis_title="Flip count")
    st.plotly_chart(fig, width="stretch")
