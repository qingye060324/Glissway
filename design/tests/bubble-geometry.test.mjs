// No npm dependencies. Run: node --test design/tests/bubble-geometry.test.mjs
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import test from 'node:test';

const html=readFileSync(new URL('../ui-preview.html',import.meta.url),'utf8');
const source=html.slice(html.indexOf('function bubbleRest('),html.indexOf('// Device gravity'));
const solve=new Function(`const clamp=(v,a,b)=>Math.max(a,Math.min(b,v)),rad=v=>v*Math.PI/180;
  const cfg={maxAngle:45,sensitivity:1};${source};return solveBubble;`)();
const sizes=[[393,852],[320,568],[360,640],[412,915],[852,393],[768,1024]];
const poses=[[0,0],[-15,0],[15,0],[-30,0],[30,0],[-45,0],[45,0],
  [0,-15],[0,15],[0,-35],[0,35],[-45,-35],[-45,35],[45,-35],[45,35]];

// Independently sample the serialized SVG path, so tests cannot pass merely by
// returning a moved reference center or testing the spline's control polygon.
function renderedPolygon(path) {
  const n=path.match(/-?\d+(?:\.\d+)?/g).map(Number),points=[];let prev=n.slice(0,2);
  for(let i=2;i<n.length;i+=6) {
    const b=n.slice(i,i+2),c=n.slice(i+2,i+4),d=n.slice(i+4,i+6);
    for(let j=0;j<12;j++){const t=j/12;points.push(prev.map((v,k)=>(1-t)**3*v+3*(1-t)**2*t*b[k]+3*(1-t)*t*t*c[k]+t**3*d[k]));}
    prev=d;
  }
  return points;
}
function contains(points,[x,y]) {
  let inside=false;
  for(let i=0,j=points.length-1;i<points.length;j=i++) {
    const a=points[i],b=points[j];
    if((a[1]>y)!==(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])inside=!inside;
  }
  return inside;
}

test('default oval fits; extreme final contours expose both centers and a central region',()=>{
  for(const [W,H] of sizes)for(const [r,p] of poses) {
    const s=solve(W,H,r,p),polygon=renderedPolygon(s.path),center=[W/2,H/2],rest=[s.restCenter.x,s.restCenter.y];
    assert.equal(s.screenCenterCovered,contains(polygon,center));assert.equal(s.restCenterCovered,contains(polygon,rest));
    assert(Math.abs(s.areaError)<.001);assert(s.visibleAreaRatio>.03);
    if(r===0&&p===0){assert(s.screenCenterCovered&&s.restCenterCovered);assert(Math.abs(s.b/s.a-1.14)<1e-10);assert(s.contour.every(([x,y])=>x>=0&&x<=W&&y>=0&&y<=H));}
    if(Math.abs(r)===45||Math.abs(p)===35) {
      assert(!s.screenCenterCovered&&!s.restCenterCovered,`${W}x${H}, ${r}/${p}`);
      for(let i=0;i<=10;i++)for(let j=0;j<=10;j++)assert(!contains(polygon,[W*(.35+.03*i),H*(.4+.02*j)]),`${W}x${H}, ${r}/${p}, central grid ${i}/${j}`);
      if(r)assert(s.contactEdges[r>0?'left':'right']);if(p)assert(s.contactEdges[p>0?'top':'bottom']);
    }
  }
});

test('continuous tilt progressively crops area without center flicker or disappearance',()=>{
  for(const [W,H] of [sizes[0],sizes[1],sizes[4]])for(const [r,p] of poses.filter(([r,p])=>Math.abs(r)===45||Math.abs(p)===35)) {
    let prev=solve(W,H,0,0),transitions=0;
    for(let k=1;k<=180;k++) {
      const s=solve(W,H,r*k/180,p*k/180);
      if(s.screenCenterCovered!==prev.screenCenterCovered)transitions++;
      assert(s.visibleAreaRatio>.03);assert(s.visibleAreaRatio<=prev.visibleAreaRatio+.00005);
      assert(Math.abs(s.visibleAreaRatio-prev.visibleAreaRatio)<.02);prev=s;
    }
    assert.equal(transitions,1);
  }
});

test('full travel survives max-angle, sensitivity, direction and compound-axis changes',()=>{
  for(const maxAngle of [5,15,45,85])for(const sensitivity of [.25,1,2.5])for(const invertX of [false,true])for(const invertY of [false,true])
    for(const [r,p] of [[1,0],[-1,0],[0,1],[0,-1],[1,1],[-1,-1],[1,-1],[-1,1]]) {
      const s=solve(393,852,r*maxAngle/sensitivity,p*maxAngle/sensitivity,{maxAngle,sensitivity,invertX,invertY},34);
      assert(!s.screenCenterCovered&&!s.restCenterCovered);assert(s.visibleAreaRatio>.03);
    }
});

test('shape deforms at mild tilt and depends only on the current pose',()=>{
  const zero=solve(393,852,0,0),a=solve(393,852,15,0),b=solve(393,852,15.0001,0);
  assert(Math.max(...a.points.map(([x,y],i)=>Math.hypot(x-a.cx-(zero.points[i][0]-zero.cx),y-a.cy-(zero.points[i][1]-zero.cy))))>1);
  assert(Math.max(...a.points.map(([x,y],i)=>Math.hypot(x-b.points[i][0],y-b.points[i][1])))<.01);
  solve(393,852,-85,85);assert.deepEqual(solve(393,852,15,0),a);
});

test('soft contours have no self intersections at compound extremes',()=>{
  const cross=(a,b,c)=>(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
  for(const [r,p] of [...poses,[85,85],[-85,-85],[85,-85],[-85,85]]) {
    const points=solve(393,852,r,p).contour,N=points.length;
    for(let i=0;i<N;i++)for(let j=i+2;j<N;j++) {
      if(i===0&&j===N-1)continue;
      const a=points[i],b=points[(i+1)%N],c=points[j],d=points[(j+1)%N];
      assert(!(cross(a,b,c)*cross(a,b,d)<-1e-8&&cross(c,d,a)*cross(c,d,b)<-1e-8));
    }
  }
});
