export type GovernanceRole = "CLINICIAN" | "RESEARCHER" | "ADMIN";

export interface GovernanceSnapshot {
  ingestion: { stage: string; input: number; output: number; dropped: number }[];
  quality: { label: string; value: string; trend: string }[];
  cost: { label: string; value: string; trend: string }[];
  alerts: string[];
}

export const emptyGovernanceSnapshot: GovernanceSnapshot = {
  ingestion: [
    { stage: "Discovered", input: 0, output: 0, dropped: 0 },
    { stage: "Parsed", input: 0, output: 0, dropped: 0 },
    { stage: "De-identified", input: 0, output: 0, dropped: 0 },
    { stage: "Indexed", input: 0, output: 0, dropped: 0 }
  ],
  quality: [
    { label: "Recall@10", value: "No data", trend: "Awaiting evaluation" },
    { label: "Context precision", value: "No data", trend: "Awaiting evaluation" },
    { label: "De-identification F1", value: "No data", trend: "Awaiting evaluation" },
    { label: "Quality assertions", value: "No data", trend: "Awaiting ingestion run" }
  ],
  cost: [
    { label: "Token usage", value: "No data", trend: "Awaiting provider telemetry" },
    { label: "Estimated cost", value: "No data", trend: "Awaiting provider telemetry" },
    { label: "Cache savings", value: "No data", trend: "Awaiting cache telemetry" },
    { label: "Retry rate", value: "No data", trend: "Awaiting agent telemetry" }
  ],
  alerts: []
};
