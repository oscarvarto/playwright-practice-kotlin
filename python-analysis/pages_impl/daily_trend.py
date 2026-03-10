"""Daily Trend page — pass/fail rates over time, optionally split by source.

The first section uses ``v_daily_status_trend`` to show an overall daily
pass/fail rate line chart.  The second section joins ``test_execution`` with
``test_run`` to break the same metric down by ``trigger_source`` (e.g.
``local`` vs ``ci``).
"""

import plotly.express as px
import polars as pl
import streamlit as st

from db import query_frame


def render() -> None:
    """Render the Daily Status Trend page.

    Draws two line charts:

    1. Overall daily pass and fail rates from ``v_daily_status_trend``.
    2. Daily pass rate split by trigger source, computed from raw tables.
    """
    st.header("Daily Status Trend")

    # Overall daily trend from the view
    daily = query_frame(
        """
        SELECT
            execution_day_utc,
            total_executions,
            pass_count,
            fail_count,
            abort_count,
            disabled_count,
            pass_rate,
            fail_rate
        FROM v_daily_status_trend
        ORDER BY execution_day_utc
        """
    )

    if daily.is_empty():
        st.info("No trend data available.")
        return

    st.subheader("Overall daily trend")
    st.dataframe(daily, width="stretch", hide_index=True)

    fig = px.line(
        daily.to_pandas(),
        x="execution_day_utc",
        y=["pass_rate", "fail_rate"],
        title="Daily pass & fail rate",
        markers=True,
    )
    fig.update_layout(
        xaxis_title="Day (UTC)",
        yaxis_title="Rate",
        yaxis_range=[0, 1],
    )
    st.plotly_chart(fig, width="stretch")

    # By trigger source
    st.subheader("Daily trend by trigger source")
    by_source = query_frame(
        """
        SELECT
            substr(tr.started_at_utc, 1, 10) AS day_utc,
            tr.trigger_source,
            COUNT(*) AS total_executions,
            SUM(CASE WHEN te.canonical_status = 'PASSED' THEN 1 ELSE 0 END) AS passed_count,
            SUM(CASE WHEN te.canonical_status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count
        FROM test_execution te
        JOIN test_run tr ON tr.run_id = te.run_id
        GROUP BY 1, 2
        ORDER BY 1, 2
        """
    )

    if by_source.is_empty():
        st.info("No source-level trend data.")
        return

    by_source = by_source.with_columns(
        (pl.col("passed_count") / pl.col("total_executions")).alias("pass_rate"),
    )

    fig2 = px.line(
        by_source.to_pandas(),
        x="day_utc",
        y="pass_rate",
        color="trigger_source",
        title="Daily pass rate by trigger source",
        markers=True,
    )
    fig2.update_layout(
        xaxis_title="Day (UTC)",
        yaxis_title="Pass rate",
        yaxis_range=[0, 1],
    )
    st.plotly_chart(fig2, width="stretch")
