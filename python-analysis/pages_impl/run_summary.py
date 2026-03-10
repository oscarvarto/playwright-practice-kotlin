"""Run Summary page — overview of recent test runs.

Displays KPI metric cards for the most recent run, a full table of all
recorded runs from ``v_run_summary``, a stacked bar chart showing the
outcome distribution across runs, and a pie chart of the latest run's
status breakdown.
"""

import plotly.express as px
import polars as pl
import streamlit as st

from db import query_frame


def render() -> None:
    """Render the Run Summary page.

    Queries ``v_run_summary`` ordered by ``started_at_utc DESC`` and
    presents:

    * Four metric cards summarising the latest run.
    * A sortable data table of every run (``run_id`` column hidden).
    * A stacked bar chart of test outcomes per run when more than one
      run exists.
    * A pie chart of the latest run's execution status breakdown.
    """
    st.header("Run Summary")

    runs = query_frame(
        """
        SELECT
            run_id,
            started_at_utc,
            finished_at_utc,
            trigger_source,
            run_label,
            total_tests,
            passed_count,
            failed_count,
            aborted_count,
            disabled_count,
            average_duration_ms,
            total_duration_ms
        FROM v_run_summary
        ORDER BY started_at_utc DESC
        """
    )

    if runs.is_empty():
        st.info("No test runs recorded yet.")
        return

    # KPI cards for the most recent run
    latest = runs.row(0, named=True)
    c1, c2, c3, c4 = st.columns(4)
    c1.metric("Total tests", latest["total_tests"])
    c2.metric("Passed", latest["passed_count"])
    c3.metric("Failed", latest["failed_count"])
    c4.metric("Disabled", latest["disabled_count"])

    st.subheader("All runs")
    st.dataframe(
        runs.drop("run_id"),
        width="stretch",
        hide_index=True,
    )

    # Stacked bar chart of outcomes per run
    if runs.height > 1:
        chart_df = runs.select(
            "started_at_utc",
            "passed_count",
            "failed_count",
            "aborted_count",
            "disabled_count",
        ).to_pandas()

        chart_df = chart_df.melt(
            id_vars="started_at_utc",
            value_vars=[
                "passed_count",
                "failed_count",
                "aborted_count",
                "disabled_count",
            ],
            var_name="status",
            value_name="count",
        )

        fig = px.bar(
            chart_df,
            x="started_at_utc",
            y="count",
            color="status",
            title="Test outcomes per run",
            color_discrete_map={
                "passed_count": "#22c55e",
                "failed_count": "#ef4444",
                "aborted_count": "#f59e0b",
                "disabled_count": "#94a3b8",
            },
        )
        fig.update_layout(xaxis_title="Run start (UTC)", yaxis_title="Count")
        st.plotly_chart(fig, width="stretch")

    # Pie chart of latest run status breakdown
    st.subheader("Latest run status breakdown")
    pie_df = pl.DataFrame(
        {
            "status": ["Passed", "Failed", "Aborted", "Disabled"],
            "count": [
                latest["passed_count"],
                latest["failed_count"],
                latest["aborted_count"],
                latest["disabled_count"],
            ],
        }
    ).filter(pl.col("count") > 0)

    pie_fig = px.pie(
        pie_df.to_pandas(),
        names="status",
        values="count",
        title="Latest run execution status",
        color="status",
        color_discrete_map={
            "Passed": "#22c55e",
            "Failed": "#ef4444",
            "Aborted": "#f59e0b",
            "Disabled": "#94a3b8",
        },
    )
    st.plotly_chart(pie_fig, width="stretch")
