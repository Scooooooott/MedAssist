import { AlertTriangle, BarChart3, Database, ShieldAlert } from "lucide-react";

import { Alert } from "../../components/Alert";
import { Panel } from "../../components/Panel";
import { emptyGovernanceSnapshot, type GovernanceRole, type GovernanceSnapshot } from "./types";

interface GovernanceDashboardProps {
  role: GovernanceRole;
  snapshot?: GovernanceSnapshot;
}

function MetricList({ items }: { items: GovernanceSnapshot["quality"] }) {
  return (
    <div className="governance-metrics">
      {items.map((item) => (
        <article className="governance-metric" key={item.label}>
          <span>{item.label}</span>
          <strong>{item.value}</strong>
          <small>{item.trend}</small>
        </article>
      ))}
    </div>
  );
}

export function GovernanceDashboard({
  role,
  snapshot = emptyGovernanceSnapshot
}: GovernanceDashboardProps) {
  if (role === "CLINICIAN") {
    return (
      <Alert tone="danger" title="Governance dashboard unavailable">
        This dashboard is restricted to governance and quality roles.
      </Alert>
    );
  }

  return (
    <div className="governance-layout">
      {role === "ADMIN" ? (
        <Panel title="Governance" actions={<ShieldAlert aria-label="Admin only" size={18} />}>
          <div className="governance-funnel" aria-label="Ingestion funnel">
            {snapshot.ingestion.map((stage) => (
              <div className="funnel-row" key={stage.stage}>
                <span>{stage.stage}</span>
                <strong>{stage.output}</strong>
                <small>{stage.dropped} dropped</small>
              </div>
            ))}
          </div>
          {snapshot.alerts.length > 0 ? (
            <div className="governance-alerts">
              {snapshot.alerts.map((alert) => (
                <Alert key={alert} tone="warning" title="Governance alert">
                  <AlertTriangle aria-hidden="true" size={16} /> {alert}
                </Alert>
              ))}
            </div>
          ) : (
            <Alert tone="neutral" title="No governance alerts">
              Audit, policy, and leakage indicators have no reported events.
            </Alert>
          )}
        </Panel>
      ) : null}

      <Panel title="Quality" actions={<BarChart3 aria-hidden="true" size={18} />}>
        <MetricList items={snapshot.quality} />
      </Panel>

      <Panel title="Cost" actions={<Database aria-hidden="true" size={18} />}>
        <MetricList items={snapshot.cost} />
      </Panel>
    </div>
  );
}
