"""Duration Analysis page — slow tests and runtime variability.

Computes per-test duration statistics (mean, standard deviation, min, max,
and coefficient of variation) from raw ``test_execution.duration_ms`` values.
A high mean identifies inherently slow tests; a high coefficient of
variation (CV) highlights tests whose runtime is unstable across runs.
"""

import plotly.express as px
import polars as pl
import streamlit as st

from db import query_frame


def render() -> None:
    """Render the Duration Analysis page.

    Presents a sortable statistics table and a bar chart of the top 15
    slowest tests by mean duration, with error bars showing one standard
    deviation.
    """
    st.header("Duration Analysis")

    durations = query_frame(
        """
        SELECT
            tc.class_name,
            tc.method_name,
            te.duration_ms
        FROM test_execution te
        JOIN test_case tc ON tc.test_case_id = te.test_case_id
        WHERE te.duration_ms IS NOT NULL
        """
    )

    if durations.is_empty():
        st.info("No duration data available.")
        return

    stats = (
        durations.group_by("class_name", "method_name")
        .agg(
            pl.len().alias("execution_count"),
            pl.col("duration_ms").mean().alias("mean_duration_ms"),
            pl.col("duration_ms").std().alias("std_duration_ms"),
            pl.col("duration_ms").min().alias("min_duration_ms"),
            pl.col("duration_ms").max().alias("max_duration_ms"),
        )
        .with_columns(
            pl.when(pl.col("mean_duration_ms") > 0)
            .then(pl.col("std_duration_ms") / pl.col("mean_duration_ms"))
            .otherwise(None)
            .alias("cv_duration")
        )
    )

    sort_col = st.selectbox(
        "Sort by",
        ["mean_duration_ms", "cv_duration", "max_duration_ms"],
        index=0,
    )

    stats_sorted = stats.sort(sort_col, descending=True, nulls_last=True)
    st.dataframe(stats_sorted, width="stretch", hide_index=True)

    # Bar chart: top 15 slowest tests by mean duration
    top_slow = stats_sorted.head(15).to_pandas()
    top_slow["label"] = top_slow["class_name"].str.rsplit(".", n=1).str[-1] + "." + top_slow["method_name"]

    fig = px.bar(
        top_slow,
        x="label",
        y="mean_duration_ms",
        error_y="std_duration_ms",
        title="Top 15 slowest tests (mean duration)",
    )
    fig.update_layout(
        xaxis_title="Test",
        yaxis_title="Mean duration (ms)",
        xaxis_tickangle=-45,
    )
    st.plotly_chart(fig, width="stretch")
