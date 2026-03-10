"""Test Case Quality page — longitudinal pass/fail and duration stats per test.

Reads from ``v_test_case_quality`` which aggregates every execution for each
logical test case into pass/fail rates, duration statistics, and the
timestamp of the last failure.
"""

import streamlit as st

from db import query_frame


def render() -> None:
    """Render the Test Case Quality page.

    Shows two summary metrics (total tracked cases and cases with at
    least one failure) followed by a full data table sorted by
    ``fail_rate`` descending.
    """
    st.header("Test Case Quality")

    quality = query_frame(
        """
        SELECT
            class_name,
            method_name,
            total_executions,
            pass_rate,
            fail_rate,
            average_duration_ms,
            min_duration_ms,
            max_duration_ms,
            last_failed_at
        FROM v_test_case_quality
        ORDER BY fail_rate DESC, average_duration_ms DESC
        """
    )

    if quality.is_empty():
        st.info("No test case data yet.")
        return

    # Summary metrics
    total_cases = quality.height
    cases_with_failures = quality.filter(quality["fail_rate"].is_not_null() & (quality["fail_rate"] > 0)).height

    c1, c2 = st.columns(2)
    c1.metric("Tracked test cases", total_cases)
    c2.metric("Cases with failures", cases_with_failures)

    st.dataframe(quality, width="stretch", hide_index=True)
