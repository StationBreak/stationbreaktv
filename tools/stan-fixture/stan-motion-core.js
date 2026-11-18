// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

// Stan, as a list of plain shapes. No DOM, no drawing API: the same logic is ported line for line to Kotlin (StanShapes.kt, StanScenes.kt).
// Colours are tokens (cream, ink, accent, grey, black); numbers are in Stan's own space, cabinet 240 x 180 centred on 0,0.
const R1=v=>{const r=Math.round(v*10)/10;return r===0?0:r;};
const R2=v=>{const r=Math.round(v*100)/100;return r===0?0:r;};
const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v)), ramp=(t,a,b)=>clamp((t-a)/(b-a));
const back=x=>{const c=1.70158;return 1+(c+1)*Math.pow(x-1,3)+c*Math.pow(x-1,2);};
const CAP="M-42 -100 C-44 -142 -100 -178 -150 -152 C-172 -140 -184 -108 -184 -76 C-172 -104 -160 -122 -142 -120 C-130 -118 -128 -88 -130 -56 Z";
const S=(d,c,w=10,a=1)=>({k:"stroke",d,c,w,a:R2(a)});
const F=(d,c,a=1)=>({k:"fill",d,c,a:R2(a)});
const C=(x,y,r,c,a=1,sw=0)=>({k:"circle",x:R1(x),y:R1(y),r:R1(r),c,a:R2(a),sw});
const O=(x,y,rx,ry,c,a=1)=>({k:"oval",x:R1(x),y:R1(y),rx:R1(rx),ry:R1(ry),c,a:R2(a)});
const RR=(x,y,w,h,r,c,rot=0,px=0,py=0,sc="",sw=0)=>({k:"rrect",x:R1(x),y:R1(y),w,h,r,c,rot:R1(rot),px:R1(px),py:R1(py),sc,sw});
const G=(ch,x,y,size,c,a=1,mid=false)=>({k:"glyph",ch,x:R1(x),y:R1(y),size:R1(size),c,a:R2(a),mid});
const starD=(x,y,r)=>{const q=r*.28;return `M${R1(x)} ${R1(y-r)} L${R1(x+q)} ${R1(y-q)} L${R1(x+r)} ${R1(y)} L${R1(x+q)} ${R1(y+q)} L${R1(x)} ${R1(y+r)} L${R1(x-q)} ${R1(y+q)} L${R1(x-r)} ${R1(y)} L${R1(x-q)} ${R1(y-q)} Z`;};
const earTip=(bx,deg,len)=>{const a=deg*Math.PI/180;return [bx+len*Math.sin(a),-96-len*Math.cos(a)];};

function stanShapes(s){const o=[],h=s.h==null?64:s.h;
  // legs and shoes
  if(s.legs==="sit"){o.push(S("M-30 86 L-33 116","cream"),S("M30 86 L33 116","cream"),RR(-52,104,34,46,15,"accent",-8,-35,127),RR(18,104,34,46,15,"accent",8,35,127));}
  else if(s.legs==="kick"){const a=(s.kick||43)*Math.PI/180,ex=55+63*Math.sin(a),ey=86+63*Math.cos(a);
    o.push(S("M-55 86 L-55 150","cream"),RR(-91,146,46,20,10,"accent"),S(`M55 86 L${R1(ex)} ${R1(ey)}`,"cream"),RR(ex-14,ey-6,46,20,10,"accent",5-(s.kick||43),ex+9,ey+4));}
  else{o.push(S(`M-55 86 L-55 ${R1(86+h)}`,"cream"),S(`M55 86 L55 ${R1(86+h)}`,"cream"),RR(-91,82+h,46,20,10,"accent"),RR(45,82+h,46,20,10,"accent"));}
  // rabbit ears
  let L,R;const d=s.droop||0;
  if(s.ears==="droop"){L=[-104,-120+d];R=[104,-118+d];o.push(S(`M-6 -96 Q-58 -152 -104 ${R1(-120+d)}`,"cream",9),S(`M6 -96 Q58 -152 104 ${R1(-118+d)}`,"cream",9));}
  else{L=earTip(-6,-34.3+(s.earL||0),99.3);o.push(S(`M-6 -96 L${R1(L[0])} ${R1(L[1])}`,"cream",9));
    if(s.ears==="zig"){R=[60,-188];o.push(S("M6 -96 L42 -138 L20 -156 L60 -188","cream",9));}
    else{R=earTip(6,30+(s.earR||0),104);o.push(S(`M6 -96 L${R1(R[0])} ${R1(R[1])}`,"cream",9));}}
  o.push(C(L[0],L[1],9,"accent"),C(R[0],R[1],9,"accent"),C(0,-88,22,"cream"));
  if(s.cap!=null){o.push({k:"push",rot:R1(s.cap),px:-86,py:-78},F(CAP,"accent"),{k:"clip",d:CAP},S("M-150 -74 L-30 -132","cream",11),S("M-160 -110 L-50 -162","cream",11),S("M-200 -112 L-150 -160","cream",11),{k:"pop"},C(-184,-62,16,"cream"),{k:"pop"});}
  // arms and hands (drawn behind the cabinet)
  const arm=(dd,x,y)=>{o.push(S(dd,"cream"),C(x,y,12,"cream"));},b=s.armBob||0;
  if(s.arms==="tune"){const hx=R[0]+20,hy=R[1];arm("M-116 10 Q-166 22 -130 52",-128,54);arm(`M116 -10 Q190 -60 ${R1(hx+74)} ${R1(hy+46)} Q${R1(hx+54)} ${R1(hy+10)} ${R1(hx)} ${R1(hy)}`,hx,hy);
    o.push(S(`M${R1(R[0]-24)} ${R1(R[1]-26)} Q${R1(R[0]-12)} ${R1(R[1]-38)} ${R1(R[0]+2)} ${R1(R[1]-34)}`,"accent",6),S(`M${R1(R[0]-30)} ${R1(R[1]-10)} Q${R1(R[0]-34)} ${R1(R[1]-24)} ${R1(R[0]-26)} ${R1(R[1]-34)}`,"accent",6));}
  if(s.arms==="rest"){arm("M-116 10 Q-150 40 -128 76",-126,78);arm("M116 10 Q150 40 128 76",126,78);}
  if(s.arms==="bear")arm("M-116 10 Q-150 40 -128 76",-126,78);
  if(s.arms==="wave"){const hx=170+14*Math.sin(s.wave),hy=-74+5*Math.cos(s.wave*2);arm("M-116 10 Q-160 30 -150 70",-150,72);arm(`M116 0 Q165 -20 ${R1(hx)} ${R1(hy+4)}`,hx,hy);
    o.push(S(`M${R1(hx+20)} ${R1(hy-30)} Q${R1(hx+30)} ${R1(hy-18)} ${R1(hx+26)} ${R1(hy-4)}`,"accent",6),S(`M${R1(hx-22)} ${R1(hy-34)} Q${R1(hx-30)} ${R1(hy-22)} ${R1(hx-24)} ${R1(hy-8)}`,"accent",6));}
  if(s.arms==="shrug"){arm(`M-116 0 Q-160 -4 -176 ${R1(-46+b)}`,-177,-50+b);arm(`M116 0 Q160 -4 176 ${R1(-46+b)}`,177,-50+b);}
  if(s.arms==="flail"){arm(`M-116 0 Q-170 -30 -160 ${R1(-82+b)}`,-160,-86+b);arm(`M116 10 Q160 44 178 ${R1(2-b)}`,179,-2-b);}
  // cabinet, screen, controls
  o.push(RR(-120,-90,240,180,36,"cream"),RR(-100,-70,144,136,30,"ink"),C(82,-42,13,"accent"),C(82,-4,13,"ink"),S("M68 34 L96 34","ink",5),S("M68 46 L96 46","ink",5),S("M68 58 L96 58","ink",5));
  // face, faded out by any static on the screen
  const f=s.face,fa=1-(s.snow||0),ex=[-58,2];
  for(const x of ex){
    if(f.eyes==="open"){const k=f.open==null?1:f.open;o.push(O(x,-14,12,Math.max(1.5,16*k),"cream",fa));if(k>.5)o.push(C(x+f.px,-14+f.py,6,"ink",fa));}
    if(f.eyes==="happy")o.push(S(`M${x-14} -8 Q${x} -28 ${x+14} -8`,"cream",7,fa));
    if(f.eyes==="sleep")o.push(S(`M${x-14} -16 Q${x} 2 ${x+14} -16`,"cream",7,fa));
    if(f.eyes==="x")o.push(S(`M${x-11} -25 L${x+11} -3 M${x+11} -25 L${x-11} -3`,"cream",7,fa));}
  if(f.brows)o.push(S("M-72 -42 L-46 -46","cream",6,fa),S("M-10 -52 L16 -42","cream",6,fa));
  if(f.mouth==="smile")o.push(S("M-52 18 Q-28 44 -4 18","cream",7,fa));
  if(f.mouth==="small")o.push(S("M-48 22 Q-28 40 -8 22","cream",7,fa));
  if(f.mouth==="tongue")o.push(S("M-46 24 Q-28 36 -10 24","cream",7,fa),C(-11,31,7,"accent",fa));
  if(f.mouth==="o")o.push(C(-28,28,f.r||7,"cream",fa,6));
  if(f.mouth==="flat")o.push(S("M-46 32 L-12 26","cream",7,fa));
  if(f.mouth==="wavy"){const A=f.wob==null?12:f.wob;o.push(S(`M-54 30 Q-45 ${R1(30-A)} -36 30 Q-27 ${R1(30+A)} -18 30 Q-9 ${R1(30-A)} 0 30`,"cream",7,fa));}
  if(f.cheeks)o.push(C(-86,12,9,"accent",fa),C(30,12,9,"accent",fa));
  if(s.snow>0)o.push({k:"snow",a:R2(s.snow),tile:s.tile,roll:Math.round(s.roll*100)/100});
  // things held in front of the cabinet
  if(s.bandage)o.push(RR(-128,-84,48,16,5,"grey",45,-104,-76,"ink",3),RR(-128,-84,48,16,5,"grey",-45,-104,-76,"ink",3));
  if(s.bear){const bx=142,by=92;for(const [dx,dy,r] of [[-15,-56,9],[15,-56,9],[0,-40,21],[-20,0,9],[20,0,9],[-15,28,10],[15,28,10]])o.push(C(bx+dx,by+dy,r,"grey"));
    o.push(O(bx,by+6,22,26,"grey"),O(bx,by-33,9,7,"cream"),C(bx,by-36,3,"ink"),C(bx-8,by-45,2.6,"ink"),C(bx+8,by-45,2.6,"ink"),
      F(`M${bx} ${by-17} L${bx-12} ${by-24} L${bx-12} ${by-10} Z M${bx} ${by-17} L${bx+12} ${by-24} L${bx+12} ${by-10} Z`,"accent"),
      S("M116 2 Q186 14 184 70 Q182 102 160 104","cream"),C(158,104,12,"cream"));}
  return o;}

// Each scene: time in seconds -> one frame. Stage space is 1920 x 1080.
// frame = {bg, back:[shapes in stage space], shadow:{cx,cy,rx,ry}, x,y,sc,rot,sy, stan:[shapes], blocks, title:{a1,a2,a3,dy}|null, fade}
const SCENE={
  standby(t){const p=t%5.2;
    const snow=p<1.3?1:p<1.7?1-(p-1.3)/.4:p<3.9?0:p<4.4?(Math.sin(p*70)>0?.85:.25):1;
    const busy=snow>.05?1:.25,look=p>2.3&&p<3.5;
    const s={arms:"tune",earR:busy*(6*Math.sin(t*9)+3*Math.sin(t*2.3)),earL:busy*3.5*Math.sin(t*9+1.4),snow,tile:Math.floor(t*14)%6,roll:(t*.9)%1,
      face:{eyes:"open",px:look?0:4,py:look?1:-8,mouth:look?"smile":"tongue",cheeks:look}};
    return {bg:"L",back:[],shadow:{cx:560,cy:862,rx:330,ry:34},x:560,y:R1(580+2.5*Math.sin(t*3)),sc:1.72,rot:0,sy:1,stan:stanShapes(s),blocks:Math.min(7,Math.floor(t/.45)%10),title:null,fade:1};},
  offair(t){const b=Math.sin(t*1.5),sy=1+.014*b,tw=i=>.55+.45*Math.sin(t*1.7+i*2.1);
    const s={legs:"sit",arms:"bear",bear:true,cap:3*b,earR:1.5*b,face:{eyes:"sleep",mouth:"o",r:6.5+1.5*b,cheeks:true}},shapes=stanShapes(s);
    for(let i=0;i<3;i++){const ph=((t/3.6)+i/3)%1;shapes.push(G("Z",112+ph*110,-112-ph*150,34+ph*50,"accent",Math.sin(Math.PI*ph)));}
    const back=[{k:"crescent",x:1640,y:200,r:70,ox:1668,oy:176,or:62,c:"cream"}];
    [[1420,150,14],[1790,330,10],[1180,230,9],[300,180,11],[880,130,8]].forEach(([x,y,r],i)=>back.push(F(starD(x,y,r),"cream",tw(i))));
    return {bg:"L",back,shadow:{cx:560,cy:862,rx:330,ry:34},x:560,y:R1(606-(sy-1)*230),sc:1.72,rot:4,sy:Math.round(sy*10000)/10000,stan:shapes,blocks:null,title:null,fade:1};},
  nosignal(t){const p=t%6,snow=p>5.3?(Math.sin(p*60)>0?.7:.2):0,b=5*Math.sin(t*2.2);
    const s={ears:"droop",droop:3*Math.sin(t*1.6),arms:"shrug",armBob:b,snow,tile:Math.floor(t*14)%6,roll:(t*.9)%1,
      face:{eyes:"open",px:6*Math.sin(t*1.1),py:0,mouth:"flat",brows:true}},shapes=stanShapes(s);
    shapes.push(G("?",0,-150+4*Math.sin(t*3),96*(1+.05*Math.sin(t*3)),"accent",1,true));
    return {bg:"L",back:[],shadow:{cx:560,cy:862,rx:330,ry:34},x:560,y:R1(580+2*Math.sin(t*2.2)),sc:1.72,rot:0,sy:1,stan:shapes,blocks:null,title:null,fade:1};},
  trouble(t){const p=t%3.2,snow=p<.25?.8:(p>1.4&&p<1.55)?.5:0;
    const s={legs:"kick",kick:43+8*Math.sin(t*5),ears:"zig",earL:3*Math.sin(t*5),arms:"flail",armBob:8*Math.sin(t*6),bandage:true,snow,tile:Math.floor(t*14)%6,roll:(t*.9)%1,
      face:{eyes:"x",mouth:"wavy",wob:12*Math.cos(t*8)}},shapes=stanShapes(s);
    [[-196,-150,22],[196,-110,16],[150,-208,12],[-150,120,12]].forEach(([x,y,r],i)=>shapes.push(F(starD(x,y,r*(.85+.3*Math.sin(t*4+i*1.7))),"accent",.6+.4*Math.sin(t*4+i*1.7))));
    for(let i=0;i<3;i++){const ph=((t/2.4)+i/3)%1;shapes.push(C(92+10*Math.sin(ph*6),-214-ph*90,12+ph*18,"grey",.5*(1-ph)));}
    return {bg:"L",back:[],shadow:{cx:560,cy:862,rx:330,ry:34},x:R1(560+1.5*Math.sin(t*23)),y:580,sc:1.72,rot:R1(-7+2.5*Math.sin(t*5)),sy:1,stan:shapes,blocks:null,title:null,fade:1};},
  opening(t){const p=t%6.4,h=64*Math.max(0,back(ramp(p,1,1.5))),e=ramp(p,1,1.4);
    const s={h,arms:p>1.25?"wave":"none",wave:t*7,earL:p<1?0:4*Math.sin(t*5)*e,earR:p<1?0:-4*Math.sin(t*5)*e,
      face:p<.6?{eyes:"sleep",mouth:"o",cheeks:true}:p<1.3?{eyes:"open",open:ramp(p,.6,.8),px:0,py:1,mouth:"small",cheeks:true}:{eyes:"happy",mouth:"smile",cheeks:true}};
    const a1=ramp(p,1.4,1.8);
    return {bg:"C",back:[],shadow:{cx:960,cy:652,rx:R1(200+h),ry:20},x:960,y:R1(650-(102+h)*1.5),sc:1.5,rot:0,sy:1,stan:stanShapes(s),blocks:null,
      title:{a1:R2(a1),a2:R2(ramp(p,1.6,2)),a3:R2(ramp(p,2.2,2.6)),dy:R1(18*(1-a1))},fade:R2(1-ramp(p,5.6,6.2))};}};


const COL={cream:"#F3EEE3",ink:"#1C1B19",accent:"#E8642C",grey:"#CFCBC2",black:"#000"},DISPLAY="Ranchers,Impact,sans-serif",MONO="'Space Mono',monospace";
function draw(shapes){let o="";for(const s of shapes){const c=COL[s.c];
  if(s.k==="stroke")o+=`<path d="${s.d}" fill="none" stroke="${c}" stroke-width="${s.w}" stroke-linecap="round" stroke-linejoin="round" opacity="${s.a}"/>`;
  else if(s.k==="fill")o+=`<path d="${s.d}" fill="${c}" opacity="${s.a}"/>`;
  else if(s.k==="circle")o+=s.sw?`<circle cx="${s.x}" cy="${s.y}" r="${s.r}" fill="none" stroke="${c}" stroke-width="${s.sw}" opacity="${s.a}"/>`:`<circle cx="${s.x}" cy="${s.y}" r="${s.r}" fill="${c}" opacity="${s.a}"/>`;
  else if(s.k==="oval")o+=`<ellipse cx="${s.x}" cy="${s.y}" rx="${s.rx}" ry="${s.ry}" fill="${c}" opacity="${s.a}"/>`;
  else if(s.k==="rrect")o+=`<rect x="${s.x}" y="${s.y}" width="${s.w}" height="${s.h}" rx="${s.r}" fill="${c}"${s.sc?` stroke="${COL[s.sc]}" stroke-width="${s.sw}"`:""}${s.rot?` transform="rotate(${s.rot} ${s.px} ${s.py})"`:""}/>`;
  else if(s.k==="glyph")o+=`<text x="${s.x}" y="${s.y}" font-family="${DISPLAY}" font-size="${s.size}" fill="${c}" opacity="${s.a}"${s.mid?' text-anchor="middle"':""}>${s.ch}</text>`;
  else if(s.k==="snow")o+=`<g clip-path="url(#scr)" opacity="${s.a}"><image href="${NOISE[s.tile]}" x="-100" y="-70" width="144" height="136" preserveAspectRatio="none" style="image-rendering:pixelated"/><rect x="-100" y="${-70+s.roll*136}" width="144" height="22" fill="${COL.cream}" opacity=".22"/></g>`;
  else if(s.k==="crescent")o+=`<circle cx="${s.x}" cy="${s.y}" r="${s.r}" fill="${c}" mask="url(#mn)"/>`;
  else if(s.k==="push")o+=`<g transform="rotate(${s.rot} ${s.px} ${s.py})">`;
  else if(s.k==="clip"){const id="cl"+(clipN++%50);o+=`<clipPath id="${id}"><path d="${s.d}"/></clipPath><g clip-path="url(#${id})">`;}
  else if(s.k==="pop")o+="</g>";}
  return o;}
const P=(d,c,w)=>`<path d="${d}" fill="none" stroke="${c}" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round"/>`;
function copy(lines,label,sub,clock,n){const y0=lines.length===2?470:545,yb=y0+(lines.length-1)*150;
  return lines.map((t,i)=>`<text x="1000" y="${y0+i*150}" font-family="${DISPLAY}" font-size="156" fill="${COL.cream}">${t}</text>`).join("")
   +P(`M1004 ${yb+52} q18 -20 36 0 t36 0 t36 0 t36 0 t36 0 t36 0`,COL.accent,9)
   +`<text x="1004" y="${yb+124}" font-family="${MONO}" font-weight="700" font-size="32" fill="${COL.accent}" letter-spacing="4">${label}</text><text x="1004" y="${yb+168}" font-family="${MONO}" font-weight="700" font-size="28" fill="${COL.grey}" letter-spacing="2">${sub}</text>`
   +(n==null?"":Array.from({length:7},(_,i)=>`<rect x="${1004+i*50}" y="${yb+196}" width="30" height="30" fill="${i<n?COL.accent:"none"}" stroke="${COL.grey}" stroke-width="3"/>`).join(""))
   +`<text x="96" y="1000" font-family="${DISPLAY}" font-size="46" fill="${COL.cream}">Station Break</text><text x="1824" y="1000" text-anchor="end" font-family="${MONO}" font-weight="700" font-size="28" fill="${COL.grey}">${clock}</text>`;}
const INFO={
 standby:{label:"Please stand by",copy:[["PLEASE","STAND BY"],"TUNING CHANNEL 212","ORDER IN THE COURT","9:47 PM"],what:"He wiggles the aerial until the picture comes in, gets a moment of clear screen, then loses it again. The blocks count up and start over."},
 opening:{label:"Opening",what:"The load screen. He is dozing, opens his eyes, stands up and waves while the name comes in. About two seconds, then it would dissolve to the guide. It loops here so you can watch it again."},
 offair:{label:"Off the air",copy:[["GONE TO BED"],"OFF THE AIR","ORDER IN THE COURT RETURNS AT 6:00 AM","2:14 AM"],what:"Dead air between schedules. He breathes with his bear under his arm, the Zs drift up and the stars twinkle. Slow enough to leave on all night."},
 nosignal:{label:"No signal",copy:[["ANYBODY","OUT THERE?"],"NO SIGNAL","CAN'T REACH YOUR SERVER · OK TO RETRY","9:47 PM"],what:"He looks one way, then the other, and shrugs. Every few seconds his screen flickers as he tries again."},
 trouble:{label:"Technical difficulties",copy:[["WELL, THAT'S","NOT RIGHT"],"TECHNICAL DIFFICULTIES","WE'LL BE RIGHT BACK · TRYING AGAIN","9:47 PM"],what:"On the fritz. He wobbles, sparks blink, smoke drifts off his bent ear and his screen cuts to static now and then."}};
function scene(k,t){const f=SCENE[k](t),i=INFO[k];
  let o=draw(f.back)+`<ellipse cx="${f.shadow.cx}" cy="${f.shadow.cy}" rx="${f.shadow.rx}" ry="${f.shadow.ry}" fill="#000" opacity=".4"/>`
   +`<g transform="translate(${f.x} ${f.y}) rotate(${f.rot}) scale(${f.sc} ${f.sc*f.sy})">${draw(f.stan)}</g>`;
  if(i.copy)o+=copy(...i.copy,f.blocks);
  if(f.title)o+=`<text x="960" y="${880+f.title.dy}" text-anchor="middle" font-family="${DISPLAY}" font-size="150"><tspan fill="${COL.cream}" opacity="${f.title.a1}">Station </tspan><tspan fill="${COL.accent}" opacity="${f.title.a2}">Break</tspan></text>`
    +`<text x="960" y="952" text-anchor="middle" font-family="${MONO}" font-weight="700" font-size="28" fill="${COL.grey}" letter-spacing="10" opacity="${f.title.a3}">PUT YOUR FEET UP</text>`;
  return {svg:`<g opacity="${f.fade}">${o}</g>`,bg:f.bg};}
module.exports={stanShapes,SCENE,INFO,COL};
