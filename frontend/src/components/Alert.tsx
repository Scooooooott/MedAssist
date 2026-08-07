import type { ReactNode } from "react";

interface AlertProps {
  tone: "neutral" | "danger" | "warning";
  title: string;
  children: ReactNode;
}

export function Alert({ tone, title, children }: AlertProps) {
  return (
    <div className={`alert alert-${tone}`} role={tone === "danger" ? "alert" : "status"}>
      <strong>{title}</strong>
      <p>{children}</p>
    </div>
  );
}
