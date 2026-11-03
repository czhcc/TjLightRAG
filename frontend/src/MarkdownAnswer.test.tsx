import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import MarkdownAnswer from "./MarkdownAnswer";

it("renders headings, lists, tables, emphasis and fenced code", () => {
  const { container } = render(<MarkdownAnswer text={'## 结论\n\n**重点**与`变量`\n\n- 第一项\n- 第二项\n\n| 名称 | 数值 |\n| --- | --- |\n| 项目 | 12 |\n\n```java\nSystem.out.println("hello");\n```\n\n依据[1]'} />);
  expect(screen.getByRole("heading", { name: "结论", level: 2 })).toBeTruthy();
  expect(screen.getAllByRole("listitem")).toHaveLength(2);
  expect(screen.getByRole("table")).toBeTruthy();
  expect(container.querySelector("strong")?.textContent).toBe("重点");
  expect(container.querySelector("pre code")?.textContent).toContain('System.out.println("hello");');
  expect(screen.getByText("依据[1]")).toBeTruthy();
});

it("escapes raw HTML and disables unsafe links and remote images", () => {
  const { container } = render(<MarkdownAnswer text={'<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[危险](javascript:alert%281%29) [安全](https://example.com)\n\n![图片](https://example.com/tracker.png)'} />);
  expect(container.querySelector("script,img,iframe")).toBeNull();
  expect(container.innerHTML).not.toMatch(/href="javascript:/i);
  expect(screen.getByRole("link", { name: "安全" }).getAttribute("href")).toBe("https://example.com");
  expect(container.textContent).toContain("图片");
});

it("updates incomplete streamed Markdown when more content arrives", () => {
  const { container, rerender } = render(<MarkdownAnswer text={'```js\nconst value ='} />);
  expect(container.querySelector("pre code")?.textContent).toContain("const value =");
  rerender(<MarkdownAnswer text={'```js\nconst value = 1;\n```\n\n**完成**'} />);
  expect(container.querySelector("pre code")?.textContent).toContain("const value = 1;");
  expect(container.querySelector("strong")?.textContent).toBe("完成");
});
