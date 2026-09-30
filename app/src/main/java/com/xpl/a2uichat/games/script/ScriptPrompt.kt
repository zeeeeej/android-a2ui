package com.xpl.a2uichat.games.script

object ScriptPrompt {
    fun instructions() = """
        动态游戏优先使用 ScriptGame。围棋、黑白棋、扫雷、五子棋等棋盘/网格玩法可以直接编写 JavaScript 规则，不再受 RuleGame 操作符限制；不要回答规则引擎不支持围棋，也不要只给文字说明。
        2048 和贪吃蛇仍优先使用原专用组件。3D、物理、联网多人尚不支持。ScriptGame 的界面全部原生 Compose，没有 HTML、DOM、WebView、外部模块、网络、存储、Java/Android API。
        组件示意：{"id":"root","component":"ScriptGame","gameId":"root","title":"游戏名称","spec":{"version":1,"source":"(() => { ...; return {init,legalActions,step,view,evaluate}; })()","config":{},"botPlayer":2,"tickMs":0,"tests":[]}}。
        spec 是对象；source 是完整 JavaScript 表达式字符串，不能使用 export/import，也不能省略函数实现。只允许源码，不能加载字节码。botPlayer 可省略（本地双人/单人）；有电脑时必填 evaluate。tickMs 可省略或为100..2000，不能与 botPlayer 同时开启。
        JS 接口精确如下：
        init(config,seed) -> JSON对象 state。所有持久状态都在 state；每次调用是新的虚拟机，不能依赖闭包/global 的可变状态。常量和纯辅助函数可定义在闭包中。Date 和 Math.random 不可用，需要随机时用 seed 初始化 state.rng 并自行实现有界确定性整数 PRNG。
        legalActions(state) -> 动作对象数组，最多512项、不重复。动作必须有 type（英文标识符<=32），点击为 {type:"tap",x:0起整数,y:0起整数}；也支持 {type:"pass"}/{type:"confirm"}/{type:"resume"}/{type:"tick"} 等自定义动作。玩家和电脑共享完整合法动作，不能禁止玩家2动作。
        step(state,action) -> {accepted:true,state:新state}；非法动作返回 {accepted:false,state:原state}，不能只返回state。引擎会先检查 action 在 legalActions 中。状态是完整快照，不能是增量。
        view(state) -> {width:2..20,height:2..20,cells:[行优先颜色索引],palette:["#RRGGBB",...],style:"cells"或"intersections",labels:[每格短字符串],marks:[每格0无/1上一步红点/2死子红叉],status:"最多160字",phase:"playing"或"scoring"或"finished",turn:1或2,winner:null或0平局或1或2,score:有限数字,buttons:[{label:"按钮名",action:{type:"pass"}}],tapType:"tap"}。
        width*height 必须等于 cells.length；palette 至少3最多16色，0为空背景、1黑、2白。labels/marks 可省略，否则与cells等长。style/labels/marks/tapType/score/winner可省略。buttons<=8，只能使用 view.buttons，不能写 controls/actions 等其他字段。每个非格子点击、非tick的合法动作必须有对应按钮，否则用户无法操作，会校验失败。控制按钮 action 必须同时在 legalActions 里。结束时 phase=finished，winner填写结果，legalActions=[]。
        evaluate(state,player) -> 有限数字，本方越有利越大。用于本地休闲电脑，最多2层搜索、12个候选、500ms预算，不是专业棋力。不要自己执行搜索；终局评估和落子合法性要一致。
        棋盘只支持单击，没有长按或右键。扫雷插旗可用按钮切换 state.flagMode，然后 step 的 tap 根据模式执行插旗/取消或揭开；legalActions 只列当前可单击的 tap，不能同时列出无对应控件的每格 flag/unflag。也可随模式改变 view.tapType，并只列该 type 的格子动作。
        源码<=64KiB，state<=128KiB，JSON深度<=16、节点<=20000；只允许有限数值（绝对值<=1e12）、字符串、布尔、null、数组、对象。禁止undefined、函数、Promise、循环引用。单操作200ms，电脑500ms，校验2秒；保持 legalActions 和 view 高效。
        tests 可选：最多16个 {seed:整数,actions:[最多64个动作],expect:{phase:"playing",turn:2,cells:[[x,y,颜色]],absentActions:[必须非法的动作]}}。不要写 tests 函数。expect.absentActions 会验证这些动作不在最终合法动作中；actions 通常是合法走子，也可包含 expect.absentActions 明确列出的禁手，这些禁手必须被拒绝且保持状态不变。不能把未声明的非法动作放进 actions。
        围棋默认9×9、人执黑先行、电脑执白(botPlayer=2)，贴目7.5，禁止自杀、实现提子和全局同形禁着（positional superko，历史棋盘存state；停一手不受同形限制）。双方连续停一手后 phase=scoring；此阶段禁止电脑自动操作，由用户点击整块棋标记/取消死子，buttons 提供 confirm确认计分 和 resume恢复对局，两个动作必须合法。按面积法统计活子及单色围空，双方接触的空区为中立，白加7.5；status 展示双方分数和简明规则。confirm 后 finished，resume 恢复原棋盘、清理死子标记但保留落子历史。不能把两次停一手直接判胜，不能伪称专业围棋AI。
        收到 script_repair 时更新上下文的已有 surfaceId/componentId，只修改 spec；旧对局会保留直到用户确认应用并重开。
        上述围棋规则也适用于其他路数和贴目的变体，不能只实现 playing 而遗漏 scoring 的 legalActions/step！围棋至少提供一个双方停一手后 phase=scoring 的 tests 用例。计分时所有按钮都必须有合法动作和真实处理分支，死子在 view.marks 对应位置标记为2。任何未结束状态都不能返回空合法动作。
        全局同形禁着必须比较提子后的 candidateBoard 与 state.history 中所有历史盘面，不能只比较当前盘面或错误地用 ko=新盘面。合法落子成功才追加历史，停一手不追加历史。标记死子时必须找出同色相连的整块棋并一起切换标记，不能只标记被点击的单颗。
        不要用固定游戏名称选择内部模板，实际生成完整规则；用户提出尺寸、目标、计分变化时，相应修改 source/config。不要将下列演示当作仅支持的游戏。
        最小合法 2×2 占格规则示例（这是接口演示，真实游戏须实现真实规则）：
        (()=>{function init(c,seed){return {b:[0,0,0,0],turn:1};}function legalActions(s){return s.b.flatMap((v,i)=>v?[]:[{type:'tap',x:i%2,y:Math.floor(i/2)}]);}function step(s,a){let i=a.y*2+a.x;if(s.b[i])return {accepted:false,state:s};s.b[i]=s.turn;s.turn=3-s.turn;return {accepted:true,state:s};}function view(s){let done=s.b.every(Boolean);return {width:2,height:2,cells:s.b,status:done?'结束':'轮流占格',phase:done?'finished':'playing',turn:s.turn,winner:done?0:null};}function evaluate(s,p){return s.b.filter(v=>v===p).length;}return {init,legalActions,step,view,evaluate};})()

        以下围棋完整参考仅用于学习算法。运行时不会按游戏名自动套用；生成围棋时把完整 source 放进 spec，并用 config.size/config.komi 适配用户要求，不可省略提子、历史判重或计分分支。其他游戏请编写对应的新规则。
        ${ScriptExamples.go}
        围棋 spec.tests 请包含双停和打劫用例（9路/7路都适用）：
        [{"seed":1,"actions":[{"type":"pass"},{"type":"pass"}],"expect":{"phase":"scoring"}},
        {"seed":1,"actions":[{"type":"tap","x":0,"y":1},{"type":"tap","x":1,"y":1},{"type":"tap","x":1,"y":0},{"type":"tap","x":0,"y":2},{"type":"tap","x":2,"y":1},{"type":"tap","x":2,"y":2},{"type":"pass"},{"type":"tap","x":1,"y":3},{"type":"tap","x":1,"y":2}],"expect":{"cells":[[1,1,0]],"absentActions":[{"type":"tap","x":1,"y":1}]}}]
    """.trimIndent()
}
