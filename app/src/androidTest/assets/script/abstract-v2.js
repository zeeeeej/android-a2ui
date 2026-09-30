(() => {
  function init(c,seed){return {b:[1,0,0,0,0,2],turn:1,moves:0};}
  function legalActions(s){if(s.moves>=6)return [];const from=s.b.indexOf(s.turn);return s.b.flatMap((v,to)=>v?[]:[{type:'move',from,to}]);}
  function step(s,a){s.b[a.to]=s.b[a.from];s.b[a.from]=0;s.turn=3-s.turn;s.moves++;return {accepted:true,state:s};}
  function view(s){return {width:2,height:3,cells:s.b,palette:['#EEDDBB','#AA0000','#111111'],textColors:s.b.map(()=> '#FFFFFF'),labels:s.b.map(v=>v?String(v):''),style:'intersections',turn:s.turn,phase:s.moves>=6?'finished':'playing',winner:s.moves>=6?0:null,status:'abstract',autoGrid:false,
    drawings:[{type:'line',layer:'grid',color:'#222222',x:0.1,y:0.1,x2:0.9,y2:0.9},{type:'line',layer:'grid',color:'#222222',x:0.9,y:0.1,x2:0.1,y2:0.9},
      {type:'text',layer:'decoration',color:'#222222',x:0.5,y:0.5,size:0.1,text:'TEST'},
      {type:'rect',layer:'background',color:'#EEDDBB',x:0,y:0,width:1,height:1,fill:true},
      {type:'circle',layer:'decoration',color:'#555555',x:0.5,y:0.5,radius:0.15,fill:false},
      {type:'polyline',layer:'grid',color:'#555555',points:[[0.1,0.1],[0.2,0.3],[0.4,0.1]]}],
    bindings:legalActions(s).map(a=>({action:a,clicks:[[a.from%2,Math.floor(a.from/2)],[a.to%2,Math.floor(a.to/2)]]}))};}
  function evaluate(s,p){return s.b.indexOf(p);}
  return {init,legalActions,step,view,evaluate};
})()
