import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

// OpenAPI Media Type and Parameter Objects prohibit both example annotations.
// Schema Objects use JSON Schema annotations and have different rules.
export function exampleConflicts(document) {
  const conflicts = [];
  const pointer = parts => "/" + parts.map(part => part.replaceAll("~", "~0").replaceAll("/", "~1")).join("/");
  const check = (value, path) => {
    if (Object.hasOwn(value, "example") && Object.hasOwn(value, "examples")) {
      conflicts.push(pointer(path));
    }
  };
  function visit(value, path = []) {
    if (!value || typeof value !== "object") return;
    if (Array.isArray(value)) {
      value.forEach((item, index) => visit(item, [...path, String(index)]));
      return;
    }
    if (Object.hasOwn(value, "in") && Object.hasOwn(value, "name")) check(value, path);
    for (const [key, child] of Object.entries(value)) {
      if (key === "schema" || key === "example" || key === "examples" || (path.length === 1 && path[0] === "components" && key === "schemas")) continue;
      if (key === "content" && child && typeof child === "object") {
        for (const [mediaType, media] of Object.entries(child)) {
          if (media && typeof media === "object") check(media, [...path, key, mediaType]);
        }
      }
      visit(child, [...path, key]);
    }
  }
  visit(document);
  return conflicts;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const files = process.argv.slice(2);
  if (!files.length) files.push(...["openapi-apps.json", "openapi-mobile.json"].map(name => fileURLToPath(new URL(name, import.meta.url))));
  for (const file of files) {
    const conflicts = exampleConflicts(JSON.parse(readFileSync(file, "utf8")));
    if (conflicts.length) {
      console.error(`${file}: example/examples conflict at ${conflicts.join(", ")}`);
      process.exitCode = 1;
    } else {
      console.log(`${file}: example/examples exclusivity PASS`);
    }
  }
}
