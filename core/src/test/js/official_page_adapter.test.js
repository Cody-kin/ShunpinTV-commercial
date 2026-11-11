// Execute the generated original adapter against a fake DOM; no network/browser bypass.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const script = fs.readFileSync(process.argv[2], 'utf8');
let calls = 0;
const player = {style:{}, contains: () => true, children:['video','official-ad','restriction-overlay']};
const video = {style:{},paused:true,ended:false,currentTime:2,readyState:4,videoWidth:1920,
 getBoundingClientRect:()=>({width:900,height:500}),play:()=>{calls++;return Promise.resolve();}};
const context = {location:{protocol:'https:',port:'',hash:'',hostname:'tv.cctv.com',pathname:'/live/cctveurope/index.shtml',search:''},
 document:{querySelectorAll:()=>[video],getElementById:()=>player},Date,Math};
const run=()=>JSON.parse(vm.runInNewContext(script,context));
assert.equal(run().found,true);
assert.equal(calls,1);
assert.equal(run().time,2);
assert.equal(calls,1,'play attempt is bounded per video');
assert.equal(player.style.width,'100vw');
assert.equal(player.style.height,'100vh');
assert.deepEqual(player.children,['video','official-ad','restriction-overlay'],'all official overlays retained');
assert.equal(video.style.objectFit,'contain');
context.location.hostname='evil.example';
assert.equal(run().found,false,'script itself guards host after a navigation race');
assert.equal(calls,1);
context.location.hostname='tv.cctv.com';context.location.pathname='/live/cctv999/index.shtml';
assert.equal(run().found,false,'unverified channel path rejected');
context.location.pathname='/live/cctv1/index.shtml';context.document.querySelectorAll=()=>[];
assert.equal(run().found,false,'page without video is not playing');
console.log('Official DOM adapter: 12 assertions passed (fake DOM, no network)');
let extra=0;const eq=(a,b,msg)=>{assert.equal(a,b,msg);extra++;};
for(const provider of ['mango','youman']) {
 let now=10000,selected=false,clicks=0,hasTarget=true,correctName=true;
 const row={classList:{contains:()=>selected},getBoundingClientRect:()=>({width:200,height:90}),click:()=>{clicks++;selected=true;}};
 const label={get textContent(){return correctName?(provider==='mango'?'金鹰卡通':'优漫卡通'):'其他頻道';},closest:()=>row,click:row.click};
 const c={window:{},Date:{now:()=>now},Math,
  location:{protocol:'https:',port:'',hash:'',hostname:provider==='mango'?'www.mgtv.com':'live.jstv.com',pathname:provider==='mango'?'/live-new':'/',search:provider==='mango'?'?_source_=C':''},
  document:{querySelectorAll:s=>s==='video'?[video]:(hasTarget?[label]:[]),getElementById:()=>null}};
 const sample=()=>JSON.parse(vm.runInNewContext(script,c));
 hasTarget=false;eq(sample().found,false,'no visible channel means no claimed video');eq(clicks,0);
 hasTarget=true;correctName=false;eq(sample().found,false,'do not click an unrelated channel');eq(clicks,0);
 correctName=true;eq(sample().found,false,'selection first waits for the site');eq(clicks,1);
 eq(sample().found,false,'no immediate old-video attribution');now+=5000;
 eq(sample().found,true,'observe only after selected and settling');eq(clicks,1,'one click only');
 selected=false;eq(sample().found,false,'other selected channel is not our target');eq(clicks,1);
}
console.log('Official selector cases: '+extra+' additional assertions passed (fake DOM, no network)');
