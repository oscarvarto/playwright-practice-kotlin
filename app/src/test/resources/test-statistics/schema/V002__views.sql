CREATE VIEW IF NOT EXISTS v_run_summary AS
SELECT
  tr.run_id,
  tr.framework,
  tr.runner,
  tr.started_at_utc,
  tr.finished_at_utc,
  tr.trigger_source,
  tr.run_label,
  tr.working_directory,
  COUNT(te.execution_id) AS total_tests,
  SUM(CASE WHEN te.canonical_status = 'PASSED' THEN 1 ELSE 0 END) AS passed_count,
  SUM(CASE WHEN te.canonical_status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
  SUM(CASE WHEN te.canonical_status = 'ABORTED' THEN 1 ELSE 0 END) AS aborted_count,
  SUM(CASE WHEN te.canonical_status = 'DISABLED' THEN 1 ELSE 0 END) AS disabled_count,
  AVG(te.duration_ms) AS average_duration_ms,
  SUM(te.duration_ms) AS total_duration_ms
FROM test_run tr
LEFT JOIN test_execution te ON te.run_id = tr.run_id
GROUP BY
  tr.run_id,
  tr.framework,
  tr.runner,
  tr.started_at_utc,
  tr.finished_at_utc,
  tr.trigger_source,
  tr.run_label,
  tr.working_directory;

CREATE VIEW IF NOT EXISTS v_test_case_latest AS
SELECT
  tc.test_case_id,
  tc.class_name,
  tc.method_name,
  tc.display_name,
  te.run_id,
  te.canonical_status AS latest_status,
  te.duration_ms AS latest_duration_ms,
  te.failure_fingerprint AS latest_failure_fingerprint
FROM test_case tc
JOIN test_execution te ON te.test_case_id = tc.test_case_id
WHERE NOT EXISTS (
  SELECT 1
  FROM test_execution newer
  WHERE newer.test_case_id = te.test_case_id
    AND (
      COALESCE(newer.finished_at_utc, newer.created_at_utc) > COALESCE(te.finished_at_utc, te.created_at_utc)
      OR (
        COALESCE(newer.finished_at_utc, newer.created_at_utc) = COALESCE(te.finished_at_utc, te.created_at_utc)
        AND newer.attempt_index > te.attempt_index
      )
      OR (
        COALESCE(newer.finished_at_utc, newer.created_at_utc) = COALESCE(te.finished_at_utc, te.created_at_utc)
        AND newer.attempt_index = te.attempt_index
        AND newer.execution_id > te.execution_id
      )
    )
);

CREATE VIEW IF NOT EXISTS v_test_case_quality AS
SELECT
  tc.test_case_id,
  tc.class_name,
  tc.method_name,
  tc.display_name,
  COUNT(te.execution_id) AS total_executions,
  SUM(CASE WHEN te.canonical_status = 'PASSED' THEN 1 ELSE 0 END) AS pass_count,
  SUM(CASE WHEN te.canonical_status = 'FAILED' THEN 1 ELSE 0 END) AS fail_count,
  SUM(CASE WHEN te.canonical_status = 'ABORTED' THEN 1 ELSE 0 END) AS abort_count,
  SUM(CASE WHEN te.canonical_status = 'DISABLED' THEN 1 ELSE 0 END) AS disabled_count,
  CAST(SUM(CASE WHEN te.canonical_status = 'PASSED' THEN 1 ELSE 0 END) AS REAL) / NULLIF(COUNT(te.execution_id), 0) AS pass_rate,
  CAST(SUM(CASE WHEN te.canonical_status = 'FAILED' THEN 1 ELSE 0 END) AS REAL) / NULLIF(COUNT(te.execution_id), 0) AS fail_rate,
  AVG(te.duration_ms) AS average_duration_ms,
  MIN(te.duration_ms) AS min_duration_ms,
  MAX(te.duration_ms) AS max_duration_ms,
  MAX(tc.last_seen_at_utc) AS last_seen_at,
  MAX(CASE WHEN te.canonical_status = 'FAILED' THEN te.finished_at_utc END) AS last_failed_at
FROM test_case tc
JOIN test_execution te ON te.test_case_id = tc.test_case_id
GROUP BY tc.test_case_id, tc.class_name, tc.method_name, tc.display_name;

CREATE VIEW IF NOT EXISTS v_flaky_candidates AS
WITH transitions AS (
  SELECT
    current.test_case_id,
    current.canonical_status,
    (
      SELECT previous.canonical_status
      FROM test_execution previous
      WHERE previous.test_case_id = current.test_case_id
        AND (
          COALESCE(previous.started_at_utc, previous.created_at_utc) < COALESCE(current.started_at_utc, current.created_at_utc)
          OR (
            COALESCE(previous.started_at_utc, previous.created_at_utc) = COALESCE(current.started_at_utc, current.created_at_utc)
            AND previous.attempt_index < current.attempt_index
          )
          OR (
            COALESCE(previous.started_at_utc, previous.created_at_utc) = COALESCE(current.started_at_utc, current.created_at_utc)
            AND previous.attempt_index = current.attempt_index
            AND previous.execution_id < current.execution_id
          )
        )
      ORDER BY
        COALESCE(previous.started_at_utc, previous.created_at_utc) DESC,
        previous.attempt_index DESC,
        previous.execution_id DESC
      LIMIT 1
    ) AS previous_status
  FROM test_execution current
),
aggregated AS (
  SELECT
    transitions.test_case_id,
    SUM(CASE WHEN transitions.canonical_status = 'PASSED' THEN 1 ELSE 0 END) AS pass_count,
    SUM(CASE WHEN transitions.canonical_status = 'FAILED' THEN 1 ELSE 0 END) AS fail_count,
    SUM(CASE
      WHEN transitions.previous_status IS NOT NULL AND transitions.previous_status <> transitions.canonical_status THEN 1
      ELSE 0
    END) AS flip_count
  FROM transitions
  GROUP BY transitions.test_case_id
)
SELECT
  aggregated.test_case_id,
  tc.class_name,
  tc.method_name,
  aggregated.pass_count,
  aggregated.fail_count,
  aggregated.flip_count,
  latest.latest_status
FROM aggregated
JOIN test_case tc ON tc.test_case_id = aggregated.test_case_id
JOIN v_test_case_latest latest ON latest.test_case_id = aggregated.test_case_id
WHERE aggregated.pass_count > 0 AND aggregated.fail_count > 0;

CREATE VIEW IF NOT EXISTS v_failure_fingerprints AS
SELECT
  aggregated.failure_fingerprint,
  aggregated.occurrence_count,
  aggregated.affected_test_count,
  aggregated.first_seen_at,
  aggregated.last_seen_at,
  (
    SELECT te.exception_class
    FROM test_execution te
    WHERE te.failure_fingerprint = aggregated.failure_fingerprint
    ORDER BY COALESCE(te.finished_at_utc, te.created_at_utc) DESC, te.execution_id DESC
    LIMIT 1
  ) AS last_exception_class,
  (
    SELECT te.exception_message
    FROM test_execution te
    WHERE te.failure_fingerprint = aggregated.failure_fingerprint
    ORDER BY COALESCE(te.finished_at_utc, te.created_at_utc) DESC, te.execution_id DESC
    LIMIT 1
  ) AS sample_message
FROM (
  SELECT
    failure_fingerprint,
    COUNT(*) AS occurrence_count,
    COUNT(DISTINCT test_case_id) AS affected_test_count,
    MIN(COALESCE(finished_at_utc, created_at_utc)) AS first_seen_at,
    MAX(COALESCE(finished_at_utc, created_at_utc)) AS last_seen_at
  FROM test_execution
  WHERE failure_fingerprint IS NOT NULL
  GROUP BY failure_fingerprint
) aggregated;

CREATE VIEW IF NOT EXISTS v_daily_status_trend AS
SELECT
  SUBSTR(COALESCE(started_at_utc, created_at_utc), 1, 10) AS execution_day_utc,
  COUNT(*) AS total_executions,
  SUM(CASE WHEN canonical_status = 'PASSED' THEN 1 ELSE 0 END) AS pass_count,
  SUM(CASE WHEN canonical_status = 'FAILED' THEN 1 ELSE 0 END) AS fail_count,
  SUM(CASE WHEN canonical_status = 'ABORTED' THEN 1 ELSE 0 END) AS abort_count,
  SUM(CASE WHEN canonical_status = 'DISABLED' THEN 1 ELSE 0 END) AS disabled_count,
  CAST(SUM(CASE WHEN canonical_status = 'PASSED' THEN 1 ELSE 0 END) AS REAL) / NULLIF(COUNT(*), 0) AS pass_rate,
  CAST(SUM(CASE WHEN canonical_status = 'FAILED' THEN 1 ELSE 0 END) AS REAL) / NULLIF(COUNT(*), 0) AS fail_rate
FROM test_execution
GROUP BY SUBSTR(COALESCE(started_at_utc, created_at_utc), 1, 10);
