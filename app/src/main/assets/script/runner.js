(() => {
  'use strict';
  // Capture intrinsics before evaluating a game. No host objects are installed.
  const parse = JSON.parse, stringify = JSON.stringify, keys = Object.keys;
  const create = Object.create, isArray = Array.isArray, finite = Number.isFinite;
  const freeze = Object.freeze;
  globalThis.Date = undefined;
  Math.random = undefined;
  [Object.prototype, Array.prototype, Function.prototype, String.prototype,
   Number.prototype, Boolean.prototype, JSON, Math, Object, Array, Number].forEach(freeze);
  function clean(v, depth = 0, budget = {n:0}) {
    if (++budget.n > 20000 || depth > 16) throw Error('JSON depth/node limit');
    if (v === null || typeof v === 'string' || typeof v === 'boolean') return v;
    if (typeof v === 'number' && finite(v) && Math.abs(v) <= 1e12) return v;
    if (typeof v !== 'object') throw Error('State/view/actions must be finite JSON, not undefined/functions');
    const out = isArray(v) ? [] : create(null);
    for (const k of keys(v)) { if (k.length > 128) throw Error('JSON key limit'); out[k] = clean(v[k], depth+1, budget); }
    return out;
  }
  const clone = v => parse(stringify(clean(v)));
  const canonical = v => {
    if (v === null || typeof v !== 'object') return stringify(v);
    return isArray(v) ? '['+v.map(canonical).join(',')+']' : '{'+keys(v).sort().map(k=>stringify(k)+':'+canonical(v[k])).join(',')+'}';
  };
  return (game, request, checkpoint) => {
    const v2 = request.version === 2;
    for (const fn of ['init','legalActions','step','view']) if (typeof game[fn] !== 'function') throw Error('Missing '+fn);
    if (request.botPlayer && typeof game.evaluate !== 'function') throw Error('Bot requires evaluate(state, player)');
    function frame(s, accepted = true) {
      const state = clean(s);
      if (!state || isArray(state) || typeof state !== 'object' || stringify(state).length > 131072) throw Error('state must be object <=128 KiB');
      const actions = clean(game.legalActions(clone(state)));
      if (!isArray(actions) || actions.length > 512) throw Error('legalActions must be array <=512');
      const view = clean(game.view(clone(state)));
      if (v2) {
        const allowed = ['width','height','cells','palette','labels','marks','style','status','phase','turn','winner','score','buttons','tapType','autoGrid','drawings','textColors','bindings'];
        if (keys(view).some(k=>!allowed.includes(k))) throw Error('Unknown v2 view field');
        if (!request.players.some(p=>p.id===view.turn)) throw Error('turn must reference players');
        if (!isArray(view.textColors) || view.textColors.length!==view.width*view.height || !view.textColors.every(c=>/^#[0-9a-fA-F]{6}$/.test(c))) throw Error('Explicit textColors required');
        if (view.autoGrid!==undefined && typeof view.autoGrid!=='boolean') throw Error('autoGrid must be boolean');
        if (view.drawings!==undefined && (!isArray(view.drawings) || view.drawings.length>256)) throw Error('drawings limit');
        for (const d of (view.drawings||[])) {
          const geometry={line:['x','y','x2','y2'],polyline:['points'],rect:['x','y','width','height','fill'],circle:['x','y','radius','fill'],text:['x','y','text','size']}[d.type];
          if(!geometry||keys(d).some(k=>!geometry.concat(['type','layer','color','stroke']).includes(k))||!['background','grid','decoration'].includes(d.layer)||typeof d.color!=='string'||!/^#[0-9a-fA-F]{6}$/.test(d.color))throw Error('Invalid drawing type/fields/layer/color');
          function number(k,min=0,max=1){if(typeof d[k]!=='number'||!finite(d[k])||d[k]<min||d[k]>max)throw Error('Drawing '+k+' out of range');return d[k];}
          if(d.stroke!==undefined)number('stroke',.001,.05);
          if(d.fill!==undefined&&typeof d.fill!=='boolean')throw Error('Drawing fill must be boolean');
          if(d.type==='polyline') {
            if(!isArray(d.points)||d.points.length<2||d.points.length>64||!d.points.every(p=>isArray(p)&&p.length===2&&p.every(n=>typeof n==='number'&&finite(n)&&n>=0&&n<=1)))throw Error('Invalid polyline points');
          } else {
            const x=number('x'),y=number('y');
            if(d.type==='line'){number('x2');number('y2');}
            if(d.type==='rect'&&(x+number('width',.001)>1.000001||y+number('height',.001)>1.000001))throw Error('Rectangle outside board');
            if(d.type==='circle')number('radius',.001,.5);
            if(d.type==='text'){number('size',.005,.2);if(typeof d.text!=='string'||d.text.length<1||d.text.length>64)throw Error('Drawing text limit');}
          }
        }
        const bindings=view.bindings || [];
        if (!isArray(bindings) || bindings.length>512) throw Error('bindings limit');
        const paths=new Set(), bound=new Set();
        for(const b of bindings) {
          if(keys(b).sort().join(',')!=='action,clicks' || !actions.some(a=>canonical(a)===canonical(b.action)) || !isArray(b.clicks) || b.clicks.length<1 || b.clicks.length>2 ||
            !b.clicks.every(p=>isArray(p)&&p.length===2&&Number.isInteger(p[0])&&Number.isInteger(p[1])&&p[0]>=0&&p[0]<view.width&&p[1]>=0&&p[1]<view.height)) throw Error('Invalid complete-action binding');
          const path=canonical(b.clicks), id=canonical(b.action);
          if(paths.has(path)||bound.has(id))throw Error('Duplicate binding'); paths.add(path);bound.add(id);
        }
        if(bindings.some(a=>bindings.some(b=>a.clicks.length<b.clicks.length&&canonical(a.clicks)===canonical(b.clicks.slice(0,a.clicks.length)))))throw Error('Ambiguous click prefix');
        if((view.buttons||[]).some(b=>!actions.some(a=>canonical(a)===canonical(b.action))))throw Error('Button not legal');
      }
      if (!Number.isInteger(view.width) || !Number.isInteger(view.height) || view.width < 2 || view.width > 20 || view.height < 2 || view.height > 20)
        throw Error('view width/height must be 2..20');
      const colors = view.palette || ['#DDBB77','#222222','#FFFFFF','#66AA88'];
      if (!isArray(colors) || colors.length < 3 || colors.length > 16 || !colors.every(c => typeof c === 'string' && /^#[0-9a-fA-F]{6}$/.test(c))) throw Error('Invalid palette');
      if (!isArray(view.cells) || view.cells.length !== view.width*view.height || !view.cells.every(c => Number.isInteger(c) && c >= 0 && c < colors.length)) throw Error('Invalid view.cells');
      for (const key of ['labels','marks']) if (view[key] && (!isArray(view[key]) || view[key].length !== view.cells.length)) throw Error('Invalid '+key);
      if (view.phase && !['playing','scoring','finished'].includes(view.phase)) throw Error('Invalid phase');
      if (view.turn !== undefined && view.turn !== 1 && view.turn !== 2) throw Error('Invalid turn');
      if (view.buttons && (!isArray(view.buttons) || view.buttons.length > 8)) throw Error('Too many buttons');
      const seen = new Set();
      for (const a of actions) {
        if (!a || typeof a !== 'object' || typeof a.type !== 'string' || !/^[A-Za-z][A-Za-z0-9_]{0,31}$/.test(a.type) || stringify(a).length > 512) throw Error('Invalid action');
        const id = canonical(a); if (seen.has(id)) throw Error('Duplicate action'); seen.add(id);
        if (a.type !== 'tick' && !(v2 ? (view.bindings||[]).some(b=>canonical(b.action)===id) : (a.type === (view.tapType || 'tap') && keys(a).length === 3 && Number.isInteger(a.x) && Number.isInteger(a.y) && a.x >= 0 && a.x < view.width && a.y >= 0 && a.y < view.height)) &&
            !(view.buttons || []).some(b => canonical(b.action) === id)) throw Error(v2 ? 'Every legal action needs view.bindings or buttons, INCLUDING BOT TURN; native UI disables human input. Missing: '+stringify(a) : 'Action '+a.type+' needs a view.buttons entry');
      }
      if (view.phase !== 'finished' && !actions.length) throw Error('Non-finished state has no legal actions');
      if (view.phase === 'finished' && (actions.length || ![0,1,2].includes(view.winner))) throw Error('Finished state needs winner and empty legalActions');
      if (view.phase === 'scoring' && (!(view.buttons || []).length || !view.buttons.every(b => seen.has(canonical(b.action)))))
        throw Error('Scoring buttons must have legal actions and implemented step handlers');
      return {ok:true,state,view,actions,accepted};
    }
    function apply(s,a) {
      const f = frame(s);
      if (!f.actions.some(x => canonical(x) === canonical(a))) { f.accepted = false; return f; }
      const result = game.step(clone(s),clone(a));
      if (!result || typeof result.accepted !== 'boolean') throw Error('step must return {accepted:boolean,state:object}');
      const next=result.accepted ? frame(result.state) : frame(s,false);
      if(v2 && result.accepted) {
        if(canonical(next.state)===canonical(f.state))throw Error('Complete action did not advance state');
        if(request.turnPolicy==='alternating' && f.view.phase==='playing' && next.view.phase==='playing' && next.view.turn===f.view.turn)throw Error('Complete action must switch player');
      }
      return next;
    }
    function encode(f) { const text = stringify(clean(f)); if (text.length > 262144) throw Error('Response limit'); return text; }
    const init = seed => {
      const f=frame(game.init(clone(request.config || {}), seed));
      if(v2 && f.view.turn!==request.initialPlayer)throw Error('init must preserve initialPlayer; never execute a bot move inside init');
      return f;
    };
    if (request.op === 'init') return encode(init(request.seed));
    if (request.op === 'step') return encode(apply(request.state,request.action));
    if (request.op === 'validate') {
      const a = init(12345), b = init(12345);
      if (canonical(a) !== canonical(b)) throw Error('init is not deterministic');
      // Probe clicks AND control transitions. Repeating a control discovers pass/pass -> scoring,
      // flag-mode toggles, etc., without any game-name-specific engine branch.
      const probes = [a.actions[0],a.actions[Math.floor(a.actions.length/2)],a.actions[a.actions.length-1],
        ...(a.view.buttons || []).map(b=>b.action).filter(b=>a.actions.some(x=>canonical(x)===canonical(b))).slice(0,2)].filter(Boolean);
      for (const action of probes) {
        let f = a, current = action;
        for (let depth=0;depth<3;depth++) {
          f=apply(f.state,current);if(!f.accepted)throw Error('legalActions includes rejected action');
          if(f.view.phase==='finished')break;
          current=f.actions.find(x=>canonical(x)===canonical(current)) || f.actions[0];
        }
        // All controls exposed by the reached phase must actually execute.
        for (const b of (f.view.buttons || []).slice(0,8)) if(f.actions.some(x=>canonical(x)===canonical(b.action))) {
          if(!apply(f.state,b.action).accepted)throw Error('Button action rejected');
        }
      }
      if (request.botPlayer) { const n = game.evaluate(clone(a.state),request.botPlayer); if (!finite(n)) throw Error('evaluate must return finite number'); }
      for (const [testIndex,test] of (request.tests || []).entries()) {
        let f = test.state ? frame(clone(test.state)) : init(test.seed === undefined ? 12345 : test.seed);
        for (const [stepIndex,action] of test.actions.entries()) {
          const before=f; f=apply(f.state,action);
          const expectedRejection=(test.expect?.absentActions || []).some(x=>canonical(x)===canonical(action));
          if(!f.accepted && !expectedRejection)throw Error('Self-test '+testIndex+' step '+stepIndex+' rejected '+stringify(action)+'; turn='+before.view.turn+'; if intentional illegal test, list in expect.absentActions (not top-level); otherwise correct test action. Legal: '+stringify(before.actions.slice(0,3)).slice(0,100));
        }
        for (const k of keys(test.expect || {})) {
          if (k === 'cells') { for (const [x,y,n] of test.expect.cells) if (f.view.cells[y*f.view.width+x] !== n) throw Error('Self-test cell mismatch'); }
          else if (k === 'absentActions') { for (const a of test.expect.absentActions) if (f.actions.some(x=>canonical(x)===canonical(a))) throw Error('Self-test forbidden action was legal'); }
          else if (k === 'legalActions') { for (const a of test.expect.legalActions) if (!f.actions.some(x=>canonical(x)===canonical(a))) throw Error('Required action missing'); }
          else if (canonical(f.view[k]) !== canonical(test.expect[k])) throw Error('Self-test '+testIndex+' mismatch: '+k+' expected='+canonical(test.expect[k])+' actual='+canonical(f.view[k]));
        }
      }
      return encode(a);
    }
    if (request.op === 'bot') {
      const root = frame(request.state), player = request.botPlayer;
      if (root.view.phase !== 'playing' || root.view.turn !== player) return encode(root);
      function evaluate(f) {
        if (f.view.phase === 'finished') return f.view.winner === player ? 1e9 : f.view.winner === 0 ? 0 : -1e9;
        const n = game.evaluate(clone(f.state),player);
        if (!finite(n)) throw Error('evaluate must return finite number');
        return Math.max(-1e8,Math.min(1e8,n));
      }
      if(v2) {
        // Native deadline interrupts this bounded search; checkpoints are always complete legal turns.
        let roots=[];
        for(const action of root.actions) {
          const f=apply(root.state,action);
          if(!f.accepted)throw Error('Bot legal action rejected');
          if(!roots.length)checkpoint(encode(f));
          const score=evaluate(f); roots.push({f,score});
          if(roots.length===1||score>roots[0].score){roots.sort((a,b)=>b.score-a.score);checkpoint(encode(roots[0].f));}
        }
        roots.sort((a,b)=>b.score-a.score); roots=roots.slice(0,12);
        function search(f,depth,alpha,beta) {
          if(depth===0||f.view.phase!=='playing')return evaluate(f);
          const maximize=f.view.turn===player;
          let candidates=f.actions.slice(0,12).map(a=>{const n=apply(f.state,a);if(!n.accepted)throw Error('Rejected search action');return {f:n,score:evaluate(n)};});
          candidates.sort((a,b)=>maximize?b.score-a.score:a.score-b.score);
          let best=maximize?-Infinity:Infinity;
          for(const c of candidates){const n=search(c.f,depth-1,alpha,beta);best=maximize?Math.max(best,n):Math.min(best,n);if(maximize)alpha=Math.max(alpha,best);else beta=Math.min(beta,best);if(beta<=alpha)break;}
          return best;
        }
        let chosen=roots[0].f;
        for(let depth=2;depth<=4;depth++) {
          let best=-Infinity, next=chosen;
          for(const c of roots){const n=search(c.f,depth-1,-Infinity,Infinity);if(n>best){best=n;next=c.f;}}
          chosen=next; checkpoint(encode(chosen));
        }
        return encode(chosen);
      }
      let candidates = [], best = -Infinity;
      for (const action of root.actions) {
        const f = apply(root.state,action);
        if (!f.accepted) throw Error('Bot legal action rejected');
        if (!candidates.length) checkpoint(encode(f));
        const score = evaluate(f); candidates.push({f,score});
        if (score > best) { best = score; checkpoint(encode(f)); }
      }
      if (!candidates.length) throw Error('No legal bot action; provide pass or finished phase');
      candidates.sort((a,b)=>b.score-a.score); best = -Infinity;
      let chosen = candidates[0].f;
      for (const {f,score} of candidates.slice(0,12)) {
        let worst = score;
        if (f.view.phase === 'playing' && f.view.turn !== player && f.actions.length) {
          worst = Infinity;
          for (const a of f.actions.slice(0,12)) { const reply = apply(f.state,a); if (reply.accepted) worst = Math.min(worst,evaluate(reply)); }
        }
        if (worst > best) { best = worst; chosen = f; checkpoint(encode(f)); }
      }
      return encode(chosen);
    }
    throw Error('Unknown operation');
  };
})()
