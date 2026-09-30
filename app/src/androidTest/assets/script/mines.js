(() => {
  function around(i,n){const a=[],x=i%n,y=Math.floor(i/n);for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++)if((dx||dy)&&x+dx>=0&&x+dx<n&&y+dy>=0&&y+dy<n)a.push((y+dy)*n+x+dx);return a;}
  function init(c,seed){const n=c.size||6;return {n,mines:Array(n*n).fill(0),open:Array(n*n).fill(0),flags:Array(n*n).fill(0),rng:seed%65536,started:false,flag:false,done:false,won:false};}
  function legalActions(s){if(s.done)return [];return s.open.flatMap((v,i)=>!v&&(s.flag||!s.flags[i])?[{type:'tap',x:i%s.n,y:Math.floor(i/s.n)}]:[]).concat([{type:'flag'}]);}
  function step(s,a){if(a.type==='flag'){s.flag=!s.flag;return {accepted:true,state:s};}const i=a.y*s.n+a.x;if(s.flag){s.flags[i]=1-s.flags[i];return {accepted:true,state:s};}if(s.flags[i])return {accepted:false,state:s};
    if(!s.started){const ids=s.mines.map((_,k)=>k).filter(k=>k!==i);for(let k=0;k<Math.floor(s.n*s.n/6);k++){s.rng=(s.rng*25173+13849)%65536;const j=s.rng%ids.length;s.mines[ids.splice(j,1)[0]]=1;}s.started=true;}
    s.open[i]=1;if(s.mines[i]){s.done=true;return {accepted:true,state:s};}const queue=[i];for(let k=0;k<queue.length;k++)if(!around(queue[k],s.n).some(j=>s.mines[j]))for(const j of around(queue[k],s.n))if(!s.open[j]&&!s.flags[j]){s.open[j]=1;queue.push(j);}
    s.won=s.open.every((v,k)=>v||s.mines[k]);s.done=s.won;return {accepted:true,state:s};}
  function view(s){return {width:s.n,height:s.n,cells:s.open.map((v,i)=>v?(s.mines[i]?1:2):0),labels:s.open.map((v,i)=>s.flags[i]?'旗':v?(s.mines[i]?'雷':String(around(i,s.n).filter(j=>s.mines[j]).length)):''),status:s.done?(s.won?'成功':'踩雷'):(s.flag?'插旗模式':'揭开模式 · 首次点击安全'),turn:1,phase:s.done?'finished':'playing',winner:s.done?(s.won?1:0):null,buttons:[{label:'切换插旗',action:{type:'flag'}}]};}
  return {init,legalActions,step,view};
})()
