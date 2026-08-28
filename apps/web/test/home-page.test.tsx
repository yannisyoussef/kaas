import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import Home from "../app/page";

test("home page honestly identifies the scaffold and provides named navigation", () => {
  const html = renderToStaticMarkup(<Home />);

  assert.match(html, /<main>/);
  assert.match(html, /<h1>Execute\. Automate\. Assure\.<\/h1>/);
  assert.match(html, /Test execution remains disabled/);
  assert.match(html, /<a class="cta" href="\/dashboard">View the scaffold dashboard<\/a>/);
});
