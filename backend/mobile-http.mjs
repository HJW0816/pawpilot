import { readFileSync } from 'node:fs';
import { MobileService } from './mobile-service.mjs';
const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.webmanifest':'application/manifest+json'};
const assets={'/mobile':'index.html','/mobile/':'index.html','/mobile/app.js':'app.js','/mobile/style.css':'style.css','/mobile/icon.svg':'icon.svg','/mobile/manifest.webmanifest':'manifest.webmanifest','/mobile/sw.js':'sw.js'};
export function mobileHandler(service){
 const limits=new Map();
 return async function(req,res){
  const url=new URL(req.url,'http://localhost');const path=url.pathname;
  if(!assets[path]&&!path.startsWith('/v2/'))return false;
  const headers={'cache-control':'no-store','x-content-type-options':'nosniff','referrer-policy':'no-referrer'};
  const reply=(status,value,extra={})=>{res.writeHead(status,{...headers,'content-type':'application/json; charset=utf-8',...extra});res.end(JSON.stringify(value));return true;};
  const origin=process.env.PAWPILOT_PUBLIC_ORIGIN;
  const allowed=origin||`http://${req.headers.host}`;
  if(origin&&!/^https:\/\/[^/]+$/.test(origin))return reply(503,{error:'invalid_public_origin'});
  if(!origin&&req.headers.host&&!/^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/.test(req.headers.host))return reply(403,{error:'local_preview_only'});
  if(req.headers.origin&&req.headers.origin!==allowed)return reply(403,{error:'origin_denied'});
  if(assets[path]){
   if(req.method!=='GET')return reply(405,{error:'method_not_allowed'});
   const asset=assets[path], ext=asset.slice(asset.lastIndexOf('.'));
   res.writeHead(200,{...headers,'content-type':types[ext]+'; charset=utf-8','content-security-policy':"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; manifest-src 'self'; worker-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"});res.end(readFileSync(new URL('./mobile/'+asset,import.meta.url)));return true;
  }
  const minute=Math.floor(Date.now()/60000),key=`${req.socket.remoteAddress}:${minute}`;
  for(const k of limits.keys())if(!k.endsWith(':'+minute))limits.delete(k);
  const count=(limits.get(key)||0)+1;limits.set(key,count);if(count>180)return reply(429,{error:'rate_limited'});
  try{
   let x={};if(!['GET','DELETE'].includes(req.method)){
    if(!req.headers['content-type']?.startsWith('application/json'))return reply(415,{error:'json_required'});
    const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>750000)return reply(413,{error:'request_too_large'});chunks.push(chunk);}
    try{x=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{return reply(400,{error:'invalid_json'});}
    if(!x||typeof x!=='object'||Array.isArray(x))return reply(400,{error:'invalid_json'});
   }
   const cookie=req.headers.cookie?.split(';').map(x=>x.trim()).find(x=>x.startsWith('pawpilot_device='))?.slice(16);
   const token=req.headers.authorization?.replace(/^Bearer /,'');
   if(path==='/v2/session'&&req.method==='POST'){
    let account;try{account=service.publicAccount(service.account(cookie));}catch{}
    if(account)return reply(200,{account,pair:service.currentPair(account)});const enrolled=service.enroll();return reply(201,{account:enrolled.account},{'set-cookie':`pawpilot_device=${enrolled.token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000${origin?'; Secure':''}`});
   }
   if(path==='/v2/vehicle/pairs'&&req.method==='POST')return reply(201,service.createPair());
   const car=path.match(/^\/v2\/vehicle\/pairs\/([\w-]+)(\/ack)?$/);
   if(car){if(car[2]&&req.method==='POST')return reply(200,service.ack(car[1],token,x));if(!car[2]&&req.method==='GET')return reply(200,service.read(car[1],token));if(!car[2]&&req.method==='DELETE')return reply(200,service.revoke(car[1],token));return reply(405,{error:'method_not_allowed'});}
   const a=service.account(cookie);
   if(path==='/v2/profile'&&req.method==='PUT')return reply(200,{account:service.profile(a,x)});
   if(path==='/v2/places'&&req.method==='GET')return reply(200,{places:service.places()});
   if(path==='/v2/places'&&req.method==='POST')return reply(201,{place:service.addPlace(a,x)});
   if(path==='/v2/reviews'&&req.method==='GET')return reply(200,{reviews:service.reviews(url.searchParams.get('placeId'))});
   if(path==='/v2/reviews'&&req.method==='POST')return reply(201,{review:service.review(a,x)});
   const review=path.match(/^\/v2\/reviews\/([\w-]+)(\/report)?$/);
   if(review){if(review[2]&&req.method==='POST')return reply(200,service.report(a,review[1],x.reason));if(!review[2]&&req.method==='DELETE')return reply(200,service.deleteReview(a,review[1]));}
   if(path==='/v2/pairs/join'&&req.method==='POST')return reply(200,service.join(a,x.code));
   const pair=path.match(/^\/v2\/pairs\/([\w-]+)(\/send)?$/);
   if(pair){if(pair[2]&&req.method==='POST')return reply(200,service.send(a,pair[1],x));if(!pair[2]&&req.method==='GET')return reply(200,service.status(a,pair[1]));if(!pair[2]&&req.method==='DELETE')return reply(200,service.disconnect(a,pair[1]));}
   return reply(404,{error:'not_found'});
  }catch(error){return reply(error.status||500,{error:error.status?error.message:'service_unavailable'});}
 };
}
export function createMobileHandler(catalog){return mobileHandler(new MobileService(catalog,{file:process.env.PAWPILOT_MOBILE_DATA||new URL('./data/private/mobile.json',import.meta.url).pathname}));}
