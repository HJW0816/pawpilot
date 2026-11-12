import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { CompanionSessions } from './companion.mjs';
import { handleRequest } from './server.mjs';
const catalog = {source: 'test', asOf: '2026-07-31', facilities: [{name: 'A(서울)', kind: '놀이터'}, {name: 'B(부산)', kind: '쉼터'}]};
test('single-use pairing, separate roles, proposal only, revoke and expiry', () => {
  let now = 1000; const service = new CompanionSessions(catalog, () => now);
  const car = service.create(['A(서울)']); const phone = service.join(car.code);
  assert.throws(() => service.join(car.code));
  assert.throws(() => service.read(car.id, phone.guest));
  assert.throws(() => service.read(car.id, '가'.repeat(32)));
  assert.throws(() => service.suggest(car.id, car.owner, {}));
  assert.throws(() => service.suggest(car.id, phone.guest, {name:'B(부산)',stars:4,comment:''}));
  assert.throws(() => service.suggest(car.id, phone.guest, {name:'A(서울)',stars:6,comment:''}));
  assert.throws(() => service.suggest(car.id, phone.guest, {name:'A(서울)',stars:4,comment:'x'.repeat(81)}));
  service.suggest(car.id, phone.guest, {name:'A(서울)',stars:4,comment:'조용해요'});
  assert.equal(service.read(car.id, car.owner).suggestion.name, 'A(서울)');
  const other = service.create(['B(부산)']); assert.throws(() => service.read(car.id, other.owner));
  now += 15 * 60_000; assert.throws(() => service.read(car.id, car.owner));
  const next = service.create(['A(서울)']); service.revoke(next.id, next.owner); assert.throws(() => service.join(next.code));
  assert.throws(() => service.create(['missing'])); assert.throws(() => service.create([]));
});
async function invoke(method, url, body, token) {
  const req = Readable.from(body ? [Buffer.from(JSON.stringify(body))] : []);
  Object.assign(req, {method, url, headers: token ? {authorization:`Bearer ${token}`} : {}, socket:{remoteAddress:'test'}});
  let code, headers, result;
  await handleRequest(req, {writeHead(c,h){code=c;headers=h;},end(value){result=String(value);}});
  return {code,headers,result};
}
test('HTTP handler: create/join/propose/read/delete and protected mobile page', async () => {
  let r = await invoke('POST','/v1/companion',{candidates:['용인(강릉)']}); assert.equal(r.code,201); const car=JSON.parse(r.result);
  r=await invoke('POST','/v1/companion/join',{code:car.code}); assert.equal(r.code,200); const phone=JSON.parse(r.result);
  r=await invoke('POST',`/v1/companion/${car.id}/suggest`,{name:'용인(강릉)',stars:0,comment:'쉬어 가요'},phone.guest); assert.equal(r.code,200);
  r=await invoke('GET',`/v1/companion/${car.id}`,null,car.owner); assert.equal(JSON.parse(r.result).suggestion.name,'용인(강릉)');
  assert.equal((await invoke('GET',`/v1/companion/${car.id}`,null,phone.guest)).code,404);
  assert.equal((await invoke('DELETE',`/v1/companion/${car.id}`,null,car.owner)).code,200);
  assert.equal((await invoke('GET',`/v1/companion/${car.id}`,null,car.owner)).code,404);
  r=await invoke('GET','/companion'); assert.equal(r.code,200); assert.match(r.headers['content-security-policy'],/frame-ancestors 'none'/);
  assert.equal((await invoke('PUT','/v1/companion')).code,405);
});
