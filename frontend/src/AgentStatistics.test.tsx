import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import AgentStatistics from "./AgentStatistics";
import "./i18n";
it("renders exact large counts without conversion to Number", () => {
  render(<AgentStatistics items={[{ tool: "aggregate_objects", arguments: { filter: { types: ["人"] } }, result: { total: "9007199254740993", groups: [{ key: { value: "<b>literal</b>" }, count: "9007199254740992" }] } }]} />);
  expect(screen.getByText("9007199254740993")).toBeTruthy();
  expect(screen.getByText("9007199254740992")).toBeTruthy();
  expect(document.querySelector("b")).toBeNull();
});
