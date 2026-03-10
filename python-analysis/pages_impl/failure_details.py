"""Failure Details page — drill into payloads and artifacts for failed executions.

This page lists the most recent 50 failed executions and lets the user
select one to inspect its attached payloads (from
``test_execution_payload``) and artifact references (from
``test_execution_artifact``).  Together these tables provide full
traceability from a failure back to the inputs and outputs that were
captured during the test run.
"""

import streamlit as st

from db import query_frame


def render() -> None:
    """Render the Failure Details page.

    Shows a table of recent failures.  When the user selects an execution
    from the dropdown, two additional tables are displayed:

    * **Payloads** — structured inputs recorded via
      ``TestStats.recordInput()``.
    * **Artifacts** — file references recorded via
      ``TestStats.recordArtifactPath()``.
    """
    st.header("Failure Details")

    recent_failures = query_frame(
        """
        SELECT
            te.execution_id,
            te.finished_at_utc,
            te.failure_fingerprint,
            te.exception_class,
            te.exception_message,
            tc.class_name,
            tc.method_name
        FROM test_execution te
        JOIN test_case tc ON tc.test_case_id = te.test_case_id
        WHERE te.canonical_status = 'FAILED'
        ORDER BY te.finished_at_utc DESC
        LIMIT 50
        """
    )

    if recent_failures.is_empty():
        st.info("No failed executions recorded.")
        return

    st.subheader("Recent failures")
    st.dataframe(recent_failures, width="stretch", hide_index=True)

    # Select an execution to drill into
    exec_ids = recent_failures["execution_id"].to_list()
    # Build labels for the selectbox
    labels = [
        f"{row['class_name'].rsplit('.', 1)[-1]}.{row['method_name']}"
        f" — {row['finished_at_utc']} [{row['execution_id'][:8]}]"
        for row in recent_failures.iter_rows(named=True)
    ]
    label_to_id = dict(zip(labels, exec_ids, strict=False))

    selected_label = st.selectbox("Inspect execution", labels)
    if not selected_label:
        return

    execution_id = label_to_id[selected_label]

    # Payloads
    payloads = query_frame(
        """
        SELECT
            payload_role,
            payload_name,
            content_type,
            payload_text
        FROM test_execution_payload
        WHERE execution_id = ?
        ORDER BY created_at_utc
        """,
        (execution_id,),
    )

    st.subheader("Payloads")
    if payloads.is_empty():
        st.caption("No payloads captured for this execution.")
    else:
        st.dataframe(payloads, width="stretch", hide_index=True)

    # Artifacts
    artifacts = query_frame(
        """
        SELECT
            artifact_kind,
            artifact_path,
            description
        FROM test_execution_artifact
        WHERE execution_id = ?
        ORDER BY created_at_utc
        """,
        (execution_id,),
    )

    st.subheader("Artifacts")
    if artifacts.is_empty():
        st.caption("No artifacts captured for this execution.")
    else:
        st.dataframe(artifacts, width="stretch", hide_index=True)
