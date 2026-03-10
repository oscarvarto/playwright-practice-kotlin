"""Test Statistics Dashboard — Streamlit app for JUnit 5 test analytics.

This is the entry point for the Streamlit application.  It configures the
page layout, renders a sidebar radio menu for navigation, and delegates
rendering to the selected page module inside ``pages_impl/``.

Run locally with::

    pixi run app

or equivalently::

    pixi run streamlit run app.py
"""

import streamlit as st

st.set_page_config(
    page_title="Test Statistics",
    page_icon=":test_tube:",
    layout="wide",
)

# ---------------------------------------------------------------------------
# Sidebar navigation
# ---------------------------------------------------------------------------
PAGES: list[str] = [
    "Run Summary",
    "Test Case Quality",
    "Flaky Candidates",
    "Failure Fingerprints",
    "Duration Analysis",
    "Daily Trend",
    "Failure Details",
]
"""Available dashboard pages presented in the sidebar."""

page = st.sidebar.radio("Navigate", PAGES)

# ---------------------------------------------------------------------------
# Page routing — each branch lazily imports and renders a single page.
# ---------------------------------------------------------------------------

if page == "Run Summary":
    from pages_impl.run_summary import render

    render()
elif page == "Test Case Quality":
    from pages_impl.test_case_quality import render

    render()
elif page == "Flaky Candidates":
    from pages_impl.flaky_candidates import render

    render()
elif page == "Failure Fingerprints":
    from pages_impl.failure_fingerprints import render

    render()
elif page == "Duration Analysis":
    from pages_impl.duration_analysis import render

    render()
elif page == "Daily Trend":
    from pages_impl.daily_trend import render

    render()
elif page == "Failure Details":
    from pages_impl.failure_details import render

    render()
