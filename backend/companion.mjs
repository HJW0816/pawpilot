import { randomBytes, timingSafeEqual } from 'node:crypto';
const secret = () => randomBytes(24).toString('base64url');
const equal = (a, b) => typeof a === 'string' && /^[A-Za-z0-9_-]{32}$/.test(a) && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));
export class CompanionSessions {
  constructor(catalog, now = Date.now) { this.catalog = catalog; this.now = now; this.sessions = new Map(); }
  sweep() { for (const [id, s] of this.sessions) if (s.expiresAt <= this.now()) this.sessions.delete(id); }
  create(candidateNames) {
    this.sweep();
    if (!Array.isArray(candidateNames) || !candidateNames.length || candidateNames.length > 20 || new Set(candidateNames).size !== candidateNames.length) throw Error('invalid_candidates');
    const candidates = candidateNames.map(name => {
      const facilities = this.catalog.facilities.filter(f => f.name === name);
      if (!facilities.length) throw Error('invalid_candidates');
      return { name, facilities };
    });
    if (this.sessions.size >= 100) throw Error('capacity');
    const id = secret(), owner = secret(), code = randomBytes(8).toString('hex');
    const s = { id, owner, code, guest: null, candidates, expiresAt: this.now() + 15 * 60_000, suggestion: null };
    this.sessions.set(id, s); return { id, owner, code, expiresAt: s.expiresAt };
  }
  join(code) {
    this.sweep();
    if (typeof code !== 'string' || !/^[0-9a-f]{16}$/.test(code)) throw Error('unavailable');
    const s = [...this.sessions.values()].find(s => s.code === code && !s.guest);
    if (!s) throw Error('unavailable');
    s.guest = secret(); return { id: s.id, guest: s.guest, expiresAt: s.expiresAt, candidates: s.candidates, source: this.catalog.source, asOf: this.catalog.asOf };
  }
  get(id, token, role) {
    this.sweep(); const s = this.sessions.get(id);
    if (!s || !s[role] || !equal(token, s[role])) throw Error('unavailable');
    return s;
  }
  suggest(id, guest, input) {
    const s = this.get(id, guest, 'guest');
    if (!input || !s.candidates.some(c => c.name === input.name) || typeof input.comment !== 'string' || input.comment.length > 80 || !Number.isInteger(input.stars) || input.stars < 0 || input.stars > 5) throw Error('invalid_suggestion');
    s.suggestion = { name: input.name, comment: input.comment.trim(), stars: input.stars, receivedAt: this.now() };
    return { ok: true }; // Only a proposal. No route mutation or public review publication.
  }
  read(id, owner) { const s = this.get(id, owner, 'owner'); return { expiresAt: s.expiresAt, joined: !!s.guest, suggestion: s.suggestion }; }
  revoke(id, owner) { this.get(id, owner, 'owner'); this.sessions.delete(id); return { ok: true }; }
}
