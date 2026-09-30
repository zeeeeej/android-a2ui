(() => {
  const dirs=[[-1,-1],[0,-1],[1,-1],[-1,0],[1,0],[-1,1],[0,1],[1,1]];
  function flips(s,i,p) {if(s.b[i])return [];const n=s.n,x=i%n,y=Math.floor(i/n),all=[];for(const [dx,dy] of dirs){let xx=x+dx,yy=y+dy,a=[];while(xx>=0&&xx<n&&yy>=0&&yy<n&&s.b[yy*n+xx]===3-p){a.push(yy*n+xx);xx+=dx;yy+=dy;}if(xx>=0&&xx<n&&yy>=0&&yy<n&&s.b[yy*n+xx]===p)all.push(...a);}return all;}
  function moves(s,p){return s.b.flatMap((v,i)=>flips(s,i,p).length?[{type:'tap',x:i%s.n,y:Math.floor(i/s.n)}]:[]);}
  function init(c,seed){const n=c.size||8,b=Array(n*n).fill(0),m=n/2;b[(m-1)*n+m-1]=2;b[m*n+m]=2;b[(m-1)*n+m]=1;b[m*n+m-1]=1;return {n,b,turn:1};}
  function legalActions(s){const a=moves(s,s.turn);return a.length?a:moves(s,3-s.turn).length?[{type:'pass'}]:[];}
  function step(s,a){if(a.type==='tap'){const i=a.y*s.n+a.x,f=flips(s,i,s.turn);if(!f.length)return {accepted:false,state:s};for(const k of [i,...f])s.b[k]=s.turn;}s.turn=3-s.turn;return {accepted:true,state:s};}
  function evaluate(s,p){return s.b.reduce((a,v)=>a+(v===p?1:v===3-p?-1:0),0);}
  function view(s){const done=!legalActions(s).length,score=evaluate(s,1);return {width:s.n,height:s.n,cells:s.b,style:'intersections',palette:['#558855','#222222','#FFFFFF'],turn:s.turn,phase:done?'finished':'playing',winner:done?(score>0?1:score<0?2:0):null,status:'黑白棋 · 夹住对手棋子翻转',score,buttons:[{label:'停一手',action:{type:'pass'}}]};}
  return {init,legalActions,step,view,evaluate};
})()
