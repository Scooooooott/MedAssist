CREATE TABLE IF NOT EXISTS clinical_import_run (
  id UUID PRIMARY KEY,
  source_id TEXT NOT NULL CHECK (btrim(source_id) <> '' AND char_length(source_id) <= 256),
  status TEXT NOT NULL CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED')),
  accepted_count INTEGER NOT NULL DEFAULT 0 CHECK (accepted_count >= 0),
  quarantined_count INTEGER NOT NULL DEFAULT 0 CHECK (quarantined_count >= 0),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS clinical_patient (
  source_id TEXT NOT NULL CHECK (btrim(source_id) <> '' AND char_length(source_id) <= 256),
  resource_id TEXT NOT NULL CHECK (btrim(resource_id) <> '' AND char_length(resource_id) <= 256),
  birth_year INTEGER NOT NULL CHECK (birth_year BETWEEN 1900 AND 2100),
  age_band TEXT NOT NULL CHECK (btrim(age_band) <> '' AND char_length(age_band) <= 64),
  gender TEXT NOT NULL CHECK (btrim(gender) <> '' AND char_length(gender) <= 64),
  race TEXT,
  ethnicity TEXT,
  zip3 TEXT CHECK (zip3 IS NULL OR zip3 ~ '^[0-9]{3}$'),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, resource_id)
);

CREATE TABLE IF NOT EXISTS clinical_encounter (
  source_id TEXT NOT NULL CHECK (btrim(source_id) <> '' AND char_length(source_id) <= 256),
  resource_id TEXT NOT NULL CHECK (btrim(resource_id) <> '' AND char_length(resource_id) <= 256),
  patient_id TEXT NOT NULL CHECK (btrim(patient_id) <> '' AND char_length(patient_id) <= 256),
  type_system TEXT,
  type_code TEXT NOT NULL CHECK (btrim(type_code) <> '' AND char_length(type_code) <= 256),
  type_display TEXT,
  start_year INTEGER NOT NULL CHECK (start_year BETWEEN 1900 AND 2100),
  end_year INTEGER CHECK (end_year IS NULL OR end_year BETWEEN 1900 AND 2100),
  reason_system TEXT,
  reason_code TEXT,
  reason_display TEXT,
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, resource_id),
  CHECK (end_year IS NULL OR end_year >= start_year)
);

CREATE TABLE IF NOT EXISTS clinical_condition (
  source_id TEXT NOT NULL CHECK (btrim(source_id) <> '' AND char_length(source_id) <= 256),
  resource_id TEXT NOT NULL CHECK (btrim(resource_id) <> '' AND char_length(resource_id) <= 256),
  patient_id TEXT NOT NULL CHECK (btrim(patient_id) <> '' AND char_length(patient_id) <= 256),
  encounter_id TEXT,
  code_system TEXT,
  code TEXT NOT NULL CHECK (btrim(code) <> '' AND char_length(code) <= 256),
  code_display TEXT,
  display TEXT,
  onset_year INTEGER CHECK (onset_year IS NULL OR onset_year BETWEEN 1900 AND 2100),
  status TEXT NOT NULL CHECK (btrim(status) <> '' AND char_length(status) <= 64),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, resource_id)
);

CREATE TABLE IF NOT EXISTS clinical_medication (
  source_id TEXT NOT NULL CHECK (btrim(source_id) <> '' AND char_length(source_id) <= 256),
  resource_id TEXT NOT NULL CHECK (btrim(resource_id) <> '' AND char_length(resource_id) <= 256),
  patient_id TEXT NOT NULL CHECK (btrim(patient_id) <> '' AND char_length(patient_id) <= 256),
  encounter_id TEXT,
  code_system TEXT,
  code TEXT NOT NULL CHECK (btrim(code) <> '' AND char_length(code) <= 256),
  code_display TEXT,
  display TEXT,
  start_year INTEGER CHECK (start_year IS NULL OR start_year BETWEEN 1900 AND 2100),
  end_year INTEGER CHECK (end_year IS NULL OR end_year BETWEEN 1900 AND 2100),
  status TEXT NOT NULL CHECK (btrim(status) <> '' AND char_length(status) <= 64),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, resource_id),
  CHECK (start_year IS NULL OR end_year IS NULL OR end_year >= start_year)
);

CREATE TABLE IF NOT EXISTS clinical_observation (
  source_id TEXT NOT NULL CHECK (btrim(source_id) <> '' AND char_length(source_id) <= 256),
  resource_id TEXT NOT NULL CHECK (btrim(resource_id) <> '' AND char_length(resource_id) <= 256),
  patient_id TEXT NOT NULL CHECK (btrim(patient_id) <> '' AND char_length(patient_id) <= 256),
  encounter_id TEXT,
  code_system TEXT,
  code TEXT NOT NULL CHECK (btrim(code) <> '' AND char_length(code) <= 256),
  code_display TEXT,
  display TEXT,
  value TEXT NOT NULL CHECK (btrim(value) <> '' AND char_length(value) <= 4096),
  unit TEXT,
  observation_year INTEGER NOT NULL CHECK (observation_year BETWEEN 1900 AND 2100),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, resource_id)
);

CREATE TABLE IF NOT EXISTS clinical_quarantine (
  source_id TEXT NOT NULL CHECK (btrim(source_id) <> '' AND char_length(source_id) <= 256),
  resource_type TEXT NOT NULL CHECK (btrim(resource_type) <> '' AND char_length(resource_type) <= 64),
  resource_id TEXT NOT NULL CHECK (btrim(resource_id) <> '' AND char_length(resource_id) <= 256),
  stage TEXT NOT NULL CHECK (stage IN ('PARSE', 'PROFILE_VALIDATION', 'MAPPING')),
  reason_code TEXT NOT NULL CHECK (reason_code IN (
    'PARSE_FAILED', 'PROFILE_MISSING', 'PROFILE_MISMATCH', 'RESOURCE_TYPE_UNSUPPORTED',
    'REQUIRED_FIELD_MISSING', 'MAPPING_FAILED')),
  safe_reason TEXT NOT NULL CHECK (
    btrim(safe_reason) <> ''
    AND char_length(safe_reason) <= 256
    AND position(chr(10) IN safe_reason) = 0
    AND position(chr(13) IN safe_reason) = 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, resource_id)
);

COMMENT ON TABLE clinical_patient IS
  'Safe Harbor patient projection only: birth year, age band, ZIP3, and non-direct-identifying attributes. No full DOB or ZIP.';
COMMENT ON TABLE clinical_quarantine IS
  'Safe failure metadata only. Raw FHIR payloads, PHI values, and stack traces are prohibited.';

CREATE OR REPLACE VIEW clinical_research_condition_counts AS
SELECT code_system,
       code,
       status,
       COUNT(DISTINCT (source_id, patient_id))::BIGINT AS patient_count,
       COUNT(*)::BIGINT AS aggregate_count
  FROM clinical_condition
 GROUP BY code_system, code, status
HAVING COUNT(DISTINCT (source_id, patient_id)) >= 5;

CREATE OR REPLACE VIEW clinical_research_observation_counts AS
SELECT code_system,
       code,
       unit,
       observation_year,
       COUNT(DISTINCT (source_id, patient_id))::BIGINT AS patient_count,
       COUNT(*)::BIGINT AS aggregate_count
  FROM clinical_observation
 GROUP BY code_system, code, unit, observation_year
HAVING COUNT(DISTINCT (source_id, patient_id)) >= 5;

CREATE OR REPLACE VIEW clinical_research_encounter_counts AS
SELECT type_system,
       type_code,
       start_year,
       COUNT(DISTINCT (source_id, patient_id))::BIGINT AS patient_count,
       COUNT(*)::BIGINT AS aggregate_count
  FROM clinical_encounter
 GROUP BY type_system, type_code, start_year
HAVING COUNT(DISTINCT (source_id, patient_id)) >= 5;
