import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { prepareRequest, validateEvidence, validateAnswer, recommend } from './recommendation.mjs';

const catalog = JSON.parse(readFileSync(new URL('../app/src/main/assets/pet_rest_facilities.json', import.meta.url)));
const candidate = { id: '용인(강릉)', name: '용인(강릉)휴게소', directionConfirmed: false };
const input = (kind, restMinutes = 0) => ({ profile: { kind, breed: '', size: '', quietPreferred: true },
  restMinutes, candidates: [candidate] });
const review = { id: 'r1', placeId: candidate.id, type: 'review', rights: 'user_consent', petKind: 'cat',
  summary: '조용한 실외 공간이 있었어요.', url: 'https://example.org/reviews/1', publishedAt: '2026-10-20', rating: 4 };

test('route candidates carry official source dates; cat cannot be recommended from dog playground fact alone', () => {
  const dog = prepareRequest(input('dog'), catalog);
  const cat = prepareRequest(input('cat'), catalog);
  assert.equal(dog.candidates[0].eligible, true);
  assert.equal(cat.candidates[0].eligible, false);
  assert.equal(dog.candidates[0].sources[0].publishedAt, '2026-07-31');
  assert.equal(dog.restDue, true);
});
test('unlicensed, undated and invalid sources are rejected', () => {
  assert.throws(() => validateEvidence([{ ...review, rights: 'scraped' }]));
  assert.throws(() => validateEvidence([{ ...review, publishedAt: 'unknown' }]));
  assert.throws(() => validateEvidence([{ ...review, url: 'http://example.org' }]));
  assert.equal(validateEvidence([review])[0].petKind, 'cat');
});
test('model cannot promote unprovided locations, citations or mismatched species', () => {
  const answer = id => ({ ranked: [{ id, reasons: [{ text: '시설을 확인했어요.', sourceIds: ['r1'] }], cautions: [] }] });
  const cat = prepareRequest(input('cat'), catalog, validateEvidence([review]));
  assert.throws(() => validateAnswer(answer('임의의 휴게소'), cat));
  assert.throws(() => validateAnswer({ ranked: [{ id: candidate.id, reasons: [{ text: '추천', sourceIds: ['fabricated'] }], cautions: [] }] }, cat));
  const valid = validateAnswer(answer(candidate.id), cat);
  assert.equal(valid[0].reviewCount, 1);
  assert.equal(valid[0].averageRating, 4);
  assert.match(valid[0].cautions[0], /진입 방향/);
  assert.throws(() => validateAnswer(answer(candidate.id), prepareRequest(input('cat'), catalog)));
});
test('absence of API credentials or evidence never fabricates an AI result; rest fallback stays available', async () => {
  const offline = await recommend(input('dog'), { catalog, evidence: [], apiKey: '', model: '' });
  assert.equal(offline.status, 'not_configured');
  assert.equal(offline.generalRestSearch, true);
  assert.equal(offline.restDue, true);
  const empty = await recommend(input('cat'), { catalog, evidence: [], apiKey: 'test', model: 'mock', fetchImpl: () => { throw Error('must not call'); } });
  assert.equal(empty.status, 'insufficient_evidence');
});
test('mock model response returns only grounded reasons without an external API call', async () => {
  const answer = { ranked: [{ id: candidate.id, reasons: [{ text: '고양이 동반 후기에서 조용한 공간을 언급했어요.', sourceIds: ['r1'] }], cautions: [] }] };
  const result = await recommend(input('cat', 30), { catalog, evidence: validateEvidence([review]),
    apiKey: 'test', model: 'mock', fetchImpl: async (url, options) => {
      assert.equal(url, 'https://api.openai.com/v1/responses');
      assert.equal(JSON.parse(options.body).store, false);
      return { ok: true, json: async () => ({ status: 'completed', output: [{ content: [{ type: 'output_text', text: JSON.stringify(answer) }] }] }) };
    } });
  assert.equal(result.status, 'ready');
  assert.equal(result.ranked[0].reasons[0].sources[0].url, review.url);
});
