import http from 'node:http';
import { createMobileHandler } from './mobile-http.mjs';
import { pathToFileURL } from 'node:url';
import { CompanionSessions } from './companion.mjs';
import { readFileSync } from 'node:fs';
import { recommend, validateEvidence } from './recommendation.mjs';

const catalog = JSON.parse(readFileSync(new URL('../app/src/main/assets/pet_rest_facilities.json', import.meta.url)));
const evidence = validateEvidence(JSON.parse(readFileSync(new URL('./data/evidence.json', import.meta.url))));
const sessions = new CompanionSessions(catalog);
const mobile = createMobileHandler(catalog);
const requests = new Map();
const joinRequests = new Map();
export async function handleRequest(req, res) {
  if (await mobile(req,res)) return;
  const reply = (code, value) => { res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }); res.end(JSON.stringify(value)); };
  if (req.url === '/health' && req.method === 'GET') return reply(200, { ok: true, aiConfigured: !!(process.env.OPENAI_API_KEY && process.env.OPENAI_MODEL), evidenceCount: evidence.length });
  if (req.method === 'GET' && ['/companion', '/companion.js'].includes(req.url)) {
    res.writeHead(200, { 'content-type': req.url.endsWith('.js') ? 'text/javascript; charset=utf-8' : 'text/html; charset=utf-8',
      'cache-control': 'no-store', 'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer',
      'content-security-policy': "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'" });
    return res.end(readFileSync(new URL(req.url.endsWith('.js') ? './companion.js' : './companion.html', import.meta.url)));
  }
  const companion = req.url?.match(/^\/v1\/companion(?:\/([A-Za-z0-9_-]+)(?:\/(suggest))?)?$/);
  if (!companion && (req.url !== '/v1/recommendations' || req.method !== 'POST')) return reply(404, { error: 'not_found' });
  // Bound to loopback. Authenticate users in the gateway before exposing this service externally.
  const minute = Math.floor(Date.now() / 60_000);
  const key = `${req.socket.remoteAddress}:${minute}`;
  for (const k of requests.keys()) if (!k.endsWith(`:${minute}`)) requests.delete(k);
  const count = (requests.get(key) ?? 0) + 1; requests.set(key, count);
  if (count > 60) return reply(429, { error: 'rate_limited' });
  const chunks = []; let bytes = 0;
  try {
    for await (const chunk of req) { bytes += chunk.length; if (bytes > 65_536) return reply(413, { error: 'request_too_large' }); chunks.push(chunk); }
    let input;
    try { input = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); }
    catch { return reply(400, { error: 'invalid_json' }); }
    if (companion) {
      const [, id, action] = companion;
      const token = req.headers.authorization?.replace(/^Bearer /, '');
      try {
        if (!id && req.method === 'POST') return reply(201, sessions.create(input.candidates));
        if (id === 'join' && req.method === 'POST') {
          for (const k of joinRequests.keys()) if (!k.endsWith(`:${minute}`)) joinRequests.delete(k);
          const attempts = (joinRequests.get(key) ?? 0) + 1; joinRequests.set(key, attempts);
          if (attempts > 10) return reply(429, { error: 'rate_limited' });
          return reply(200, sessions.join(input.code));
        }
        if (action === 'suggest' && req.method === 'POST') return reply(200, sessions.suggest(id, token, input));
        if (!action && req.method === 'GET') return reply(200, sessions.read(id, token));
        if (!action && req.method === 'DELETE') return reply(200, sessions.revoke(id, token));
        return reply(405, { error: 'method_not_allowed' });
      } catch (error) { return reply(error.message === 'unavailable' ? 404 : 400, {error: error.message}); }
    }
    const result = await recommend(input, { catalog, evidence, apiKey: process.env.OPENAI_API_KEY, model: process.env.OPENAI_MODEL });
    reply(200, result);
  } catch (error) {
    const invalid = /^Invalid/.test(error.message);
    reply(invalid ? 400 : 503, { error: invalid ? 'invalid_request' : 'recommendation_unavailable', generalRestSearch: true });
  }
}
export const server = http.createServer(handleRequest);
server.requestTimeout = 30_000;
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) server.listen(Number(process.env.PORT ?? 8787), '127.0.0.1', () => console.log('PawPilot recommendation gateway: http://127.0.0.1:' + server.address().port));
