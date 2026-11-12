import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';
import { readFileSync, writeFileSync, renameSync, mkdirSync, existsSync } from 'node:fs';
import { dirname } from 'node:path';
const id = () => randomBytes(18).toString('base64url');
const hash = x => createHash('sha256').update(x).digest('hex');
const fail = (message, status = 400) => { throw Object.assign(Error(message), {status}); };
const str = (x, max, required = true) => { if (typeof x !== 'string' || x.length > max || (required && !x.trim())) fail('invalid_text'); return x.trim(); };
export const kinds = ['dog', 'cat', 'other'];
export const categories = ['휴게소', '카페', '공원', '숙소', '식당', '기타'];
const luminance = hex => hex.slice(1).match(/../g).map(c => parseInt(c,16)/255).map(c => c<=.04045?c/12.92:((c+.055)/1.055)**2.4).reduce((s,c,i)=>s+c*[.2126,.7152,.0722][i],0);
export function validateTheme(x) {
  if (!x || !/^#[0-9a-fA-F]{6}$/.test(x.accent) || !['sand','white','mint'].includes(x.surface)) fail('invalid_theme');
  if (1.05/(luminance(x.accent)+.05)<4.5) fail('theme_contrast');
  return {accent:x.accent.toUpperCase(),surface:x.surface};
}
export function validatePhoto(photo) {
  if (photo === null) return null;
  if (typeof photo !== 'string' || photo.length > 700000 || !/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(photo)) fail('invalid_photo');
  const b=Buffer.from(photo.split(',')[1],'base64');
  if(b.length<20 || b[0]!==255 || b[1]!==216 || b.at(-2)!==255 || b.at(-1)!==217) fail('invalid_photo');
  // Reject EXIF/comment payloads and unreasonable dimensions. Browser re-encodes before upload.
  let i=2, dimensions=false;
  while(i+4<b.length) {
    if(b[i++]!==255) fail('invalid_photo');
    const marker=b[i++]; if(marker===218)break;
    const len=b.readUInt16BE(i); if(len<2 || i+len>b.length)fail('invalid_photo');
    if((marker>=225&&marker<=239)||marker===254)fail('photo_metadata');
    if([192,193,194].includes(marker)) { if(len<8)fail('invalid_photo');const h=b.readUInt16BE(i+3),w=b.readUInt16BE(i+5);if(!w||!h||w>768||h>768)fail('photo_dimensions');dimensions=true; }
    i+=len;
  }
  if(!dimensions)fail('invalid_photo'); return photo;
}
export class MobileService {
  constructor(catalog, {file=null, now=Date.now}={}) {
    this.file=file;this.now=now;this.pairs=new Map();
    this.data={accounts:[],places:[],reviews:[],reports:[]};
    if(file&&existsSync(file))this.data=JSON.parse(readFileSync(file,'utf8'));
    this.official=[...new Set(catalog.facilities.map(f=>f.name))].map(name=>({id:'rest-'+hash(name).slice(0,16),name,category:'휴게소',address:'진행 방향과 위치는 내비 검색에서 확인',petKinds:['dog'],conditions:catalog.facilities.filter(f=>f.name===name).map(f=>`${f.kind} · ${f.location} · ${f.hours}`).join('\n'),source:catalog.source,asOf:catalog.asOf,verified:false,origin:'official',author:null}));
  }
  save(){if(!this.file)return;mkdirSync(dirname(this.file),{recursive:true,mode:0o700});writeFileSync(this.file+'.tmp',JSON.stringify(this.data),{mode:0o600});renameSync(this.file+'.tmp',this.file);}
  account(secret){if(typeof secret!=='string')fail('login_required',401);const a=this.data.accounts.find(a=>a.secretHash===hash(secret));if(!a)fail('login_required',401);return a;}
  enroll(){if(this.data.accounts.length>=200)fail('pilot_capacity',503);const token=id();const a={id:id(),secretHash:hash(token),name:'우리 아이',kind:'dog',photo:null,theme:{accent:'#BA3F29',surface:'sand'}};this.data.accounts.push(a);this.save();return {token,account:this.publicAccount(a)};}
  publicAccount(a){return {id:a.id,name:a.name,kind:a.kind,photo:a.photo,theme:a.theme};}
  profile(a,input){const name=str(input.name,20);if(/[\x00-\x1f\x7f]/.test(name))fail('invalid_name');if(!kinds.includes(input.kind))fail('invalid_kind');const theme=validateTheme(input.theme);const photo=validatePhoto(input.photo);Object.assign(a,{name,kind:input.kind,theme,photo});this.save();return this.publicAccount(a);}
  places(){return [...this.official,...this.data.places.filter(p=>!p.hidden)].map(p=>{const reviews=this.data.reviews.filter(r=>r.placeId===p.id&&!r.hidden);return {...p,reviewCount:reviews.length,rating:reviews.length?Number((reviews.reduce((s,r)=>s+r.stars,0)/reviews.length).toFixed(1)):null};});}
  place(id){const p=this.places().find(p=>p.id===id);if(!p)fail('place_not_found',404);return p;}
  addPlace(a,x){if(this.data.places.length>=1000)fail('pilot_capacity',503);if(!categories.includes(x.category)||!Array.isArray(x.petKinds)||!x.petKinds.length||x.petKinds.some(k=>!kinds.includes(k)))fail('invalid_place');const p={id:id(),name:str(x.name,70),address:str(x.address,160),category:x.category,petKinds:[...new Set(x.petKinds)],conditions:str(x.conditions,500),source:'이용자 등록 · 동반 조건 미확인',asOf:new Date(this.now()).toISOString().slice(0,10),origin:'community',verified:false,author:a.id,hidden:false};this.data.places.push(p);this.save();return p;}
  reviews(placeId){if(placeId)this.place(placeId);return this.data.reviews.filter(r=>!r.hidden&&(!placeId||r.placeId===placeId)).map(r=>({...r,placeName:this.place(r.placeId).name})).reverse();}
  review(a,x){this.place(x.placeId);if(x.consent!==true)fail('consent_required');if(!kinds.includes(x.kind)||!Number.isInteger(x.stars)||x.stars<1||x.stars>5)fail('invalid_review');if(!/^\d{4}-\d{2}-\d{2}$/.test(x.visitedAt)||!Number.isFinite(Date.parse(x.visitedAt))||new Date(x.visitedAt).toISOString().slice(0,10)!==x.visitedAt||x.visitedAt>new Date(this.now()).toISOString().slice(0,10))fail('invalid_visit_date');const text=str(x.text,1000);let r=this.data.reviews.find(r=>r.author===a.id&&r.placeId===x.placeId);if(!r){if(this.data.reviews.length>=5000)fail('pilot_capacity',503);r={id:id(),author:a.id,placeId:x.placeId};this.data.reviews.push(r);}Object.assign(r,{kind:x.kind,stars:x.stars,text,visitedAt:x.visitedAt,updatedAt:this.now(),hidden:!!r.hidden});this.save();return r;}
  deleteReview(a,rid){const i=this.data.reviews.findIndex(r=>r.id===rid&&r.author===a.id);if(i<0)fail('not_found',404);this.data.reviews.splice(i,1);this.save();return {ok:true};}
  report(a,rid,reason){const r=this.data.reviews.find(r=>r.id===rid);if(!r)fail('not_found',404);reason=str(reason,300);if(!this.data.reports.some(x=>x.reviewId===rid&&x.author===a.id))this.data.reports.push({reviewId:rid,author:a.id,reason,at:this.now()});r.hidden=true;this.save();return {ok:true,hidden:true};}
  sweep(){for(const [id,p]of this.pairs)if(p.expiresAt<=this.now())this.pairs.delete(id);}
  createPair(){this.sweep();if(this.pairs.size>=100)fail('pilot_capacity',503);const p={id:id(),owner:id(),code:randomBytes(8).toString('hex'),expiresAt:this.now()+900000,accountId:null,inbox:null,status:null};this.pairs.set(p.id,p);return {id:p.id,owner:p.owner,code:p.code,expiresAt:p.expiresAt};}
  currentPair(a){this.sweep();const p=[...this.pairs.values()].reverse().find(p=>p.accountId===a.id);return p?{id:p.id,expiresAt:p.expiresAt}:null;}
  join(a,code){this.sweep();const p=[...this.pairs.values()].find(p=>p.code===code&&!p.accountId);if(!p)fail('pair_unavailable',404);p.accountId=a.id;return {id:p.id,expiresAt:p.expiresAt};}
  pair(pid,token){this.sweep();const p=this.pairs.get(pid);if(!p||typeof token!=='string'||!/^[A-Za-z0-9_-]{24}$/.test(token)||token.length!==p.owner.length||!timingSafeEqual(Buffer.from(token),Buffer.from(p.owner)))fail('pair_unavailable',404);return p;}
  mobilePair(a,pid){this.sweep();const p=this.pairs.get(pid);if(!p||p.accountId!==a.id)fail('pair_unavailable',404);return p;}
  send(a,pid,x){const p=this.mobilePair(a,pid);if(p.inbox)fail('delivery_pending',409);let payload;if(x.type==='place'){const place=this.place(x.placeId);payload={type:'place',place:{id:place.id,name:place.name,address:place.origin==='official'?'':place.address,category:place.category,query:place.origin==='official'?place.name:place.name+' '+place.address}};}else if(x.type==='profile'){payload={type:'profile',profile:this.publicAccount(a)};}else fail('invalid_delivery');p.inbox={...payload,id:id(),createdAt:this.now()};p.status={id:p.inbox.id,state:'pending',type:x.type};return p.status;}
  read(pid,token){const p=this.pair(pid,token);return {expiresAt:p.expiresAt,joined:!!p.accountId,inbox:p.inbox};}
  ack(pid,token,x){const p=this.pair(pid,token);if(!p.inbox&&p.status?.id===x.id&&p.status?.state===x.state)return {ok:true};if(!p.inbox||p.inbox.id!==x.id||!['applied','search_requested','dismissed','failed'].includes(x.state))fail('invalid_ack');if(p.inbox.type==='place'&&x.state==='applied')fail('invalid_ack');if(p.inbox.type==='profile'&&x.state==='search_requested')fail('invalid_ack');p.status={id:x.id,state:x.state,type:p.inbox.type};p.inbox=null;return {ok:true};}
  status(a,pid){const p=this.mobilePair(a,pid);return {expiresAt:p.expiresAt,status:p.status};}
  revoke(pid,token){this.pair(pid,token);this.pairs.delete(pid);return {ok:true};}
  disconnect(a,pid){this.mobilePair(a,pid);this.pairs.delete(pid);return {ok:true};}
}
