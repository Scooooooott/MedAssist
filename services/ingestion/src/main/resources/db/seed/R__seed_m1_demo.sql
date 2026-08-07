INSERT INTO document (source_system, source_uri, doc_type, publisher, title)
VALUES ('synthetic', 's3://raw-documents/demo-note.txt', 'CLINICAL_NOTE', 'MedAssist Synthetic', 'Synthetic Demo Note')
ON CONFLICT (source_system, source_uri) DO NOTHING;
