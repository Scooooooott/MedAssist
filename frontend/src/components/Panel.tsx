import type { ReactNode } from "react";

interface PanelProps {
  title: string;
  actions?: ReactNode;
  children: ReactNode;
}

export function Panel({ title, actions, children }: PanelProps) {
  return (
    <section
      className="panel"
      aria-labelledby={`${title.replace(/\s+/g, "-").toLowerCase()}-title`}
    >
      <div className="panel-header">
        <h2 id={`${title.replace(/\s+/g, "-").toLowerCase()}-title`}>{title}</h2>
        {actions}
      </div>
      {children}
    </section>
  );
}
