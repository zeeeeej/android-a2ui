package com.xpl.a2uichat.games.script

/** Teaching data for the model, never selected/executed by a game-name branch. */
object ScriptExamples {
    val go = """
        (() => {
          function neighbors(i,n) { const x=i%n,y=Math.floor(i/n),a=[]; if(x)a.push(i-1);if(x+1<n)a.push(i+1);if(y)a.push(i-n);if(y+1<n)a.push(i+n);return a; }
          function group(b,i,n) { const stones=[i],seen=new Set(stones),lib=new Set();for(let j=0;j<stones.length;j++)for(const k of neighbors(stones[j],n))if(!b[k])lib.add(k);else if(b[k]===b[i]&&!seen.has(k)){seen.add(k);stones.push(k);}return {stones,lib:lib.size}; }
          function place(s,i) {
            if(s.b[i])return null; const b=s.b.slice();b[i]=s.turn;
            for(const k of neighbors(i,s.n))if(b[k]===3-s.turn){const g=group(b,k,s.n);if(!g.lib)for(const j of g.stones)b[j]=0;}
            if(!group(b,i,s.n).lib||s.history.includes(b.join('')))return null;
            return b;
          }
          function init(c,seed) { const n=c.size||9,b=Array(n*n).fill(0);return {n,b,turn:1,phase:'playing',passes:0,dead:[],history:[b.join('')],komi:typeof c.komi==='number'?c.komi:7.5,last:-1,winner:null,black:0,white:7.5}; }
          function legalActions(s) {
            if(s.phase==='finished')return [];
            const a=[];
            for(let i=0;i<s.b.length;i++)if(s.phase==='scoring'?s.b[i]:place(s,i))a.push({type:'tap',x:i%s.n,y:Math.floor(i/s.n)});
            return a.concat(s.phase==='scoring'?[{type:'confirm'},{type:'resume'}]:[{type:'pass'}]);
          }
          function score(s) {
            const b=s.b.map((v,i)=>s.dead.includes(i)?0:v),seen=new Set();let black=0,white=s.komi;
            for(let i=0;i<b.length;i++) {
              if(b[i]){if(b[i]===1)black++;else white++;continue;}
              if(seen.has(i))continue;const region=[i],border=new Set();seen.add(i);
              for(let j=0;j<region.length;j++)for(const k of neighbors(region[j],s.n))if(b[k])border.add(b[k]);else if(!seen.has(k)){seen.add(k);region.push(k);}
              if(border.size===1){if(border.has(1))black+=region.length;else white+=region.length;}
            }
            return {black,white};
          }
          function step(s,a) {
            if(s.phase==='scoring') {
              if(a.type==='tap') {const i=a.y*s.n+a.x;if(!s.b[i])return {accepted:false,state:s};const g=group(s.b,i,s.n).stones,remove=s.dead.includes(i);s.dead=remove?s.dead.filter(k=>!g.includes(k)):[...new Set(s.dead.concat(g))];}
              else if(a.type==='resume'){s.phase='playing';s.dead=[];s.passes=0;}
              else if(a.type==='confirm'){const t=score(s);s.black=t.black;s.white=t.white;s.winner=t.black>t.white?1:t.black<t.white?2:0;s.phase='finished';}
              else return {accepted:false,state:s};
            } else if(s.phase==='playing') {
              if(a.type==='pass'){s.passes++;s.turn=3-s.turn;s.last=-1;if(s.passes===2)s.phase='scoring';}
              else if(a.type==='tap'){const i=a.y*s.n+a.x,b=place(s,i);if(!b)return {accepted:false,state:s};s.b=b;s.history.push(b.join(''));s.turn=3-s.turn;s.passes=0;s.last=i;}
              else return {accepted:false,state:s};
            } else return {accepted:false,state:s};
            return {accepted:true,state:s};
          }
          function view(s) {const t=score(s);return {width:s.n,height:s.n,cells:s.b,style:'intersections',phase:s.phase,turn:s.turn,winner:s.winner,score:t.black-t.white,
            marks:s.b.map((v,i)=>s.dead.includes(i)?2:i===s.last?1:0),status:s.phase==='playing'?(s.n+'路围棋 · 黑先 · 白贴'+s.komi+' · 禁自杀/同形'):`黑 ${'$'}{t.black} / 白 ${'$'}{t.white} · ${'$'}{s.phase==='scoring'?'点击整块死子后确认':'已结束'}`,
            buttons:s.phase==='playing'?[{label:'停一手',action:{type:'pass'}}]:s.phase==='scoring'?[{label:'确认计分',action:{type:'confirm'}},{label:'恢复对局',action:{type:'resume'}}]:[]}; }
          function evaluate(s,p) {const t=score(s);return (p===1?1:-1)*(t.black-t.white);}
          return {init,legalActions,step,view,evaluate};
        })()
    """.trimIndent()
}
