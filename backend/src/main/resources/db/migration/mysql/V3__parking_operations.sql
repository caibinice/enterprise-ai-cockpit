CREATE TABLE IF NOT EXISTS parking_dataset (
  id VARCHAR(50) PRIMARY KEY,
  start_date DATE NOT NULL, end_date DATE NOT NULL,
  source_url VARCHAR(500) NOT NULL, source_sha256 VARCHAR(64) NOT NULL,
  seed BIGINT NOT NULL, manifest_json LONGTEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS parking_occupancy (
  dataset_id VARCHAR(50) NOT NULL, observed_at DATETIME NOT NULL,
  zone_id VARCHAR(8) NOT NULL, capacity INT NOT NULL, occupied INT NOT NULL,
  arrivals INT NOT NULL, departures INT NOT NULL,
  PRIMARY KEY(dataset_id, observed_at, zone_id),
  INDEX idx_parking_zone_time (dataset_id, zone_id, observed_at)
);
CREATE TABLE IF NOT EXISTS parking_stays (
  id BIGINT PRIMARY KEY, dataset_id VARCHAR(50) NOT NULL, zone_id VARCHAR(8) NOT NULL,
  vehicle_alias VARCHAR(40) NOT NULL, entered_at DATETIME NOT NULL,
  exited_at DATETIME NULL, purpose VARCHAR(30) NOT NULL,
  fee_cents INT NOT NULL DEFAULT 0, paid_cents INT NOT NULL DEFAULT 0,
  INDEX idx_parking_entry (dataset_id, entered_at),
  INDEX idx_parking_exit (dataset_id, exited_at),
  INDEX idx_parking_zone_exit (dataset_id, zone_id, exited_at)
);
CREATE TABLE IF NOT EXISTS parking_alerts (
  id BIGINT PRIMARY KEY, dataset_id VARCHAR(50) NOT NULL, zone_id VARCHAR(8) NOT NULL,
  title VARCHAR(200) NOT NULL, severity VARCHAR(10) NOT NULL, occurred_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'open',
  INDEX idx_parking_alert_time (dataset_id, occurred_at)
);
CREATE TABLE IF NOT EXISTS parking_users (
  username VARCHAR(60) PRIMARY KEY, display_name VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL, password_hash VARCHAR(150) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS parking_workorders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, alert_id BIGINT NOT NULL,
  request_key VARCHAR(64) NOT NULL UNIQUE,
  title VARCHAR(200) NOT NULL, note VARCHAR(1500) NOT NULL,
  status VARCHAR(30) NOT NULL, assigned_to VARCHAR(60) NOT NULL,
  created_by VARCHAR(60) NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
  version INT NOT NULL DEFAULT 0,
  INDEX idx_parking_work_status (status, created_at)
);
CREATE TABLE IF NOT EXISTS parking_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, actor VARCHAR(60) NOT NULL, role VARCHAR(20) NOT NULL,
  action VARCHAR(80) NOT NULL, target VARCHAR(100) NOT NULL, detail VARCHAR(1500) NOT NULL,
  occurred_at DATETIME NOT NULL, INDEX idx_parking_audit_time (occurred_at)
);
CREATE TABLE IF NOT EXISTS parking_configuration (
  id VARCHAR(50) PRIMARY KEY, version INT NOT NULL, content_json LONGTEXT NOT NULL,
  updated_by VARCHAR(60) NOT NULL, updated_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS parking_report_cache (
  period VARCHAR(20) PRIMARY KEY, content_json LONGTEXT NOT NULL, generated_at DATETIME NOT NULL
);
