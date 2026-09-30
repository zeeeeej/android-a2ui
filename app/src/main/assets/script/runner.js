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
    for (const fn of ['init','legalActions','step','view']) if (typeof game[fn] !== 'function') throw Error('Missing '+fn);
    if (request.botPlayer && typeof game.evaluate !== 'function') throw Error('Bot requires evaluate(state, player)');
    function frame(s, accepted = true) {
      const state = clean(s);
      if (!state || isArray(state) || typeof state !== 'object' || stringify(state).length > 131072) throw Error('state must be object <=128 KiB');
      const actions = clean(game.legalActions(clone(state)));
      if (!isArray(actions) || actions.length > 512) throw Error('legalActions must be array <=512');
      const view = clean(game.view(clone(state)));
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
        if (a.type !== 'tick' && !(a.type === (view.tapType || 'tap') && keys(a).length === 3 && Number.isInteger(a.x) && Number.isInteger(a.y) && a.x >= 0 && a.x < view.width && a.y >= 0 && a.y < view.height) &&
            !(view.buttons || []).some(b => canonical(b.action) === id)) throw Error('Action '+a.type+' needs a view.buttons entry');
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
      return result.accepted ? frame(result.state) : frame(s,false);
    }
    function encode(f) { const text = stringify(clean(f)); if (text.length > 262144) throw Error('Response limit'); return text; }
    const init = seed => frame(game.init(clone(request.config || {}), seed));
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
        let f = init(test.seed === undefined ? 12345 : test.seed);
        for (const [stepIndex,action] of test.actions.entries()) {
          const before=f; f=apply(f.state,action);
          const expectedRejection=(test.expect?.absentActions || []).some(x=>canonical(x)===canonical(action));
          if(!f.accepted && !expectedRejection)throw Error('Self-test '+testIndex+' step '+stepIndex+' rejected '+stringify(action)+'; legal examples: '+stringify(before.actions.slice(0,4)).slice(0,160));
        }
        for (const k of keys(test.expect || {})) {
          if (k === 'cells') { for (const [x,y,n] of test.expect.cells) if (f.view.cells[y*f.view.width+x] !== n) throw Error('Self-test cell mismatch'); }
          else if (k === 'absentActions') { for (const a of test.expect.absentActions) if (f.actions.some(x=>canonical(x)===canonical(a))) throw Error('Self-test forbidden action was legal'); }
          else if (canonical(f.view[k]) !== canonical(test.expect[k])) throw Error('Self-test mismatch: '+k);
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
