import assert from "node:assert/strict";
import test from "node:test";
import { exampleConflicts } from "./check-examples.mjs";

const response = media => ({ paths: { "/trips": { post: { responses: { 422: { content: { "application/json": media } } } } } } });

test("rejects conflicting Media Type examples even when example is null", () => {
  assert.deepEqual(exampleConflicts(response({ example: null, examples: { validation: { value: {} } } })), ["/paths/~1trips/post/responses/422/content/application~1json"]);
});

test("accepts either a single media example or named examples", () => {
  assert.deepEqual(exampleConflicts(response({ example: {} })), []);
  assert.deepEqual(exampleConflicts(response({ examples: { success: { value: {} } } })), []);
});

test("does not apply Media Type rules to JSON Schema annotations or payload values", () => {
  const schema = { type: "object", example: {}, examples: [{}] };
  assert.deepEqual(exampleConflicts({ components: { schemas: { Payload: schema } } }), []);
  assert.deepEqual(exampleConflicts(response({ schema, example: { content: { data: { example: {}, examples: {} } } } })), []);
});

test("rejects conflicting Parameter Object examples", () => {
  assert.deepEqual(exampleConflicts({ parameters: [{ in: "query", name: "page", example: 1, examples: {} }] }), ["/parameters/0"]);
});
