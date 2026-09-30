package com.xpl.a2uichat.games.script

object ScriptPrompt {
    fun instructions() = """
        动态棋盘/网格游戏使用 ScriptGame v2，规则由你根据本轮需求完整生成，不按游戏名选择固定模板。2048、贪吃蛇仍可使用专用组件。3D/物理/联网多人暂不支持。
        界面完全原生 Compose；JS 只有纯 JSON 计算，没有 DOM/WebView/网络/存储/Java/Android/模块/字节码。
        spec 精确字段：version:2,source:完整JS表达式字符串,config:对象,players:[{id:1或2,label:角色名称,color:"#RRGGBB",controller:"human"或"bot"}],initialPlayer:角色id,turnPolicy:"alternating"或"extraTurn",可选tickMs/tests。
        players 1..2个唯一角色，至少一个human，最多一个bot；alternating需要两个角色。不要输出 botPlayer，宿主从 players 推导。角色id与颜色没有隐含关系，必须遵守用户人机分工和先手要求。
        source 返回 {init,legalActions,step,view,evaluate}。无 import/export。所有可变状态必须在 state，每个调用使用新虚拟机。
        init(config,seed) -> 初始JSON对象state。绝不在init中替电脑走棋；view(init).turn必须等于initialPlayer。Date/Math.random不可用，用seed实现确定性有界PRNG。
        legalActions(state) -> 最多512个不重复完整动作对象，有type英文标识符<=32字，每个<=512字节。对双方都必须返回合法动作，不能只返回人类动作。
        完整动作例如 {type:"move",from:0,to:3}，不能把选中、取消、切换棋子放进state/step！它们属于原生点击选择。单击落子也需要bindings。
        step(state,action) -> {accepted:boolean,state:完整新state}；一次只执行一个完整动作，不能内置电脑走子或搜索。非法动作返回false且原状态不变。playing→playing时alternating必须切换turn；允许连走使用extraTurn。其他计分/设置阶段可不换手。
        view(state) 精确字段：width/height整数2..20,cells:每格颜色索引,palette:3..16个#RRGGBB（0为空背景，无固定黑白含义）,textColors:每格明确的#RRGGBB文字颜色,
        labels:可选每格<=8字,marks:可选每格0无/1红点/2红叉,style:"cells"或"intersections",autoGrid:布尔默认true,
        status:<=160字,phase:"playing"或"scoring"或"finished",turn:角色id,winner:null或0平局或获胜角色id,score:有限数,
        buttons:[{label:1..20字,action:完整合法动作}],bindings:[{action:完整合法动作,clicks:[[x,y]]或[[起点x,起点y],[终点x,终点y]]}],drawings:下面的绘图数组。禁止其他字段。
        cells/textColors/labels/marks长度为width*height。每个非tick合法动作必须且只能绑定一个无歧义点击序列，或有按钮；buttons最多8个且都合法。bindings最多512个，无重复路径、无前缀歧义。非结束状态必须有动作；结束状态有winner且动作和bindings/buttons均为空。
        电脑回合也必须提供所有合法动作对应的bindings，不能写turn===human才返回bindings的条件。宿主自动阻止人在电脑回合操作，不需要脚本隐藏控件。通常先const actions=legalActions(s)，再对actions完整map生成bindings，确保动作对象完全一致。
        坐标：点击使用0起格子坐标；绘图使用整个棋盘矩形的0..1归一化坐标。格子中心为((x+0.5)/width,(y+0.5)/height)，不是x/(width-1)。
        drawings最多256项，层layer只能为background/grid/decoration，依序在棋子之前绘制；宿主最后绘制交互提示。关闭autoGrid可自行绘制断开的网格。只允许：
        {type:"line",layer,color:"#RRGGBB",x,y,x2,y2,stroke:0.001..0.05}
        {type:"polyline",layer,color,points:[[x,y],...2..64个],stroke}
        {type:"rect",layer,color,x,y,width:0.001..1,height:0.001..1,fill:布尔,stroke}（不可越界）
        {type:"circle",layer,color,x,y,radius:0.001..0.5,fill:布尔,stroke}
        {type:"text",layer,color,x,y,text:1..64字,size:0.005..0.2}（居中定位）。stroke/radius/size按棋盘较短边计算。不得用status冒充棋盘中间文字，不得用说明冒充几何线条。
        evaluate(state,player) -> 有限数字，越有利越大。有bot时必填；宿主迭代加深至最多4个完整动作、每层最多12候选、最多1秒，超时保留合法最佳动作；不是专业棋力。单步200ms，校验2秒，源码64KiB/state128KiB/JSON深度16节点20000。禁止undefined/函数/循环引用/非有限数；数值绝对值<=1e12。
        tickMs可选0或100..2000，不能与bot并用。tests最多16个，每个{seed:整数,可选state:指定测试快照,actions:[最多64完整动作],expect:{phase,turn,cells:[[x,y,颜色]],legalActions:[必须合法动作],absentActions:[必须非法动作],其他view字段:精确期望值}}。
        tests是数据，不是函数。不得仅测试能点击；应覆盖双方的规则边界、胜负条件、特殊状态和用户要求。actions里的非法动作必须在absentActions明确列出。自动检查和独立复核不是正确性证明，不要承诺所有规则完全正确。
        特别注意 tests[i] 顶层只能有 seed/state/actions/expect，absentActions/turn/cells都必须嵌套在expect里。负例结构：{"actions":[非法动作],"expect":{"absentActions":[同一非法动作]}}。不要输出 test.absentActions 或 test.expect.accepted。
        每个测试默认从 init 独立开始，电脑不会提前走子。测试第一个动作必须属于 initialPlayer；后续动作必须按step返回的turn依次执行。生成自测时依据实际init数组和legalActions，不能凭另一种棋盘朝向猜坐标。不同棋种的编码必须唯一，初始布局、移动规则、标签和颜色映射要一致。
        收到更新/修复需求时使用已有surfaceId和componentId，仅更新spec；不要创建第二个游戏伪装修复。旧规则保留到用户点击应用新规则并重开。
        最小接口演示不是游戏模板：legalActions返回{type:"place",x,y}时，view.bindings应映射为{action:同一完整对象,clicks:[[x,y]]}；移动动作则映射两次点击。不要生成选中或取消动作。
    """.trimIndent()
}
