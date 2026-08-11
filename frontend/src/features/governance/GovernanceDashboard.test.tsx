import { render, screen } from "@testing-library/react";

import { GovernanceDashboard } from "./GovernanceDashboard";

describe("GovernanceDashboard", () => {
  it("hides governance data from clinicians", () => {
    render(<GovernanceDashboard role="CLINICIAN" />);

    expect(screen.getByText("Governance dashboard unavailable")).toBeVisible();
    expect(screen.queryByRole("heading", { name: "Quality" })).not.toBeInTheDocument();
  });

  it("shows all dashboard panels for admins", () => {
    render(<GovernanceDashboard role="ADMIN" />);

    expect(screen.getByRole("heading", { name: "Governance" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "Quality" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "Cost" })).toBeVisible();
  });

  it("shows quality and cost panels to researchers", () => {
    render(<GovernanceDashboard role="RESEARCHER" />);

    expect(screen.queryByRole("heading", { name: "Governance" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Quality" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "Cost" })).toBeVisible();
  });
});
