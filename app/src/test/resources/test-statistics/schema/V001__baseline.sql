CREATE TABLE IF NOT EXISTS test_run (
  run_id TEXT PRIMARY KEY,
  framework TEXT NOT NULL,
  runner TEXT NOT NULL,
  started_at_utc TEXT NOT NULL,
  finished_at_utc TEXT,
  trigger_source TEXT NOT NULL,
  run_label TEXT,
  working_directory TEXT NOT NULL,
  git_commit TEXT,
  git_branch TEXT,
  ci_provider TEXT,
  ci_build_id TEXT,
  ci_job_name TEXT,
  host_name TEXT,
  os_name TEXT,
  os_arch TEXT,
  jvm_vendor TEXT,
  jvm_version TEXT,
  browser_name TEXT,
  browser_channel TEXT,
  headless INTEGER
);

CREATE TABLE IF NOT EXISTS test_case (
  test_case_id TEXT PRIMARY KEY,
  framework TEXT NOT NULL,
  engine_id TEXT NOT NULL,
  unique_id TEXT NOT NULL,
  package_name TEXT,
  class_name TEXT,
  method_name TEXT,
  display_name TEXT NOT NULL,
  first_seen_at_utc TEXT NOT NULL,
  last_seen_at_utc TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS test_case_tag (
  test_case_id TEXT NOT NULL,
  tag TEXT NOT NULL,
  PRIMARY KEY (test_case_id, tag),
  FOREIGN KEY (test_case_id) REFERENCES test_case (test_case_id)
);

CREATE TABLE IF NOT EXISTS test_execution (
  execution_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  test_case_id TEXT NOT NULL,
  attempt_index INTEGER NOT NULL,
  started_at_utc TEXT,
  finished_at_utc TEXT,
  duration_ms INTEGER,
  canonical_status TEXT NOT NULL,
  framework_status TEXT NOT NULL,
  disabled_reason TEXT,
  exception_class TEXT,
  exception_message TEXT,
  exception_stacktrace TEXT,
  failure_fingerprint TEXT,
  thread_name TEXT,
  created_at_utc TEXT NOT NULL,
  FOREIGN KEY (run_id) REFERENCES test_run (run_id),
  FOREIGN KEY (test_case_id) REFERENCES test_case (test_case_id)
);

CREATE TABLE IF NOT EXISTS test_execution_payload (
  payload_id TEXT PRIMARY KEY,
  execution_id TEXT NOT NULL,
  payload_role TEXT NOT NULL,
  payload_name TEXT NOT NULL,
  content_type TEXT NOT NULL,
  payload_text TEXT NOT NULL,
  created_at_utc TEXT NOT NULL,
  FOREIGN KEY (execution_id) REFERENCES test_execution (execution_id)
);

CREATE TABLE IF NOT EXISTS test_execution_artifact (
  artifact_id TEXT PRIMARY KEY,
  execution_id TEXT NOT NULL,
  artifact_kind TEXT NOT NULL,
  artifact_path TEXT NOT NULL,
  description TEXT,
  created_at_utc TEXT NOT NULL,
  FOREIGN KEY (execution_id) REFERENCES test_execution (execution_id)
);

CREATE INDEX IF NOT EXISTS idx_test_execution_run_status
  ON test_execution (run_id, canonical_status);

CREATE INDEX IF NOT EXISTS idx_test_execution_case_started
  ON test_execution (test_case_id, started_at_utc DESC);

CREATE INDEX IF NOT EXISTS idx_test_execution_failure_fingerprint
  ON test_execution (failure_fingerprint);

CREATE INDEX IF NOT EXISTS idx_test_case_class_method
  ON test_case (class_name, method_name);

CREATE INDEX IF NOT EXISTS idx_test_case_tag_tag
  ON test_case_tag (tag);
