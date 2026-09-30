package com.xpl.a2uichat.games.rules

object RulePrompt {
    fun instructions() = """
        以下是兼容旧游戏的 RuleGame 协议，仅在用户明确指定 RuleGame 或修复旧 RuleGame 时使用；其他新棋盘/网格游戏优先使用 ScriptGame。
        RuleGame 自带游戏卡片、全屏、开始/暂停、重开、得分及点评。用它作为 root，gameId="root"，title 为游戏名，spec 是下面描述的 JSON 对象。
        仅 RuleGame.spec 禁止源代码，必须用下面的 JSON DSL。ScriptGame.spec.source 则需要 JavaScript。不能将两种协议混用。
        未支持的 3D、物理、联网多人或平台能力请明确说明；不能仅用文字/按钮假装已生成可玩游戏。

        RuleGameSpec v1 精确格式：
        {version:1,width:2..20,height:2..20,players:1或2,initial:{状态变量:初值},handlers:{事件名:[指令]},controls:[{id:事件名,label:按钮文字}],tickMs:0或100..2000,style:"cells"或"intersections",palette:["#RRGGBB",...],overlay:{shape:数组变量名,x:整数变量名,y:整数变量名,color:颜色索引},bot:{player:2,evaluate:整数表达式}}
        controls/tickMs/style/palette/overlay/bot 可省略。palette 默认8色，0=空格、1=黑、2=白、3=青。initial 的变量必须提前声明（最多32个），值只允许整数、布尔、短字符串和数组。坐标从0开始，y向下。数组最多400项。
        表达式：整数/布尔/字符串/数组字面值；读取状态用 {"var":"名字"}；读取点击坐标用 {"event":"x"} 或 {"event":"y"}。
        只读变量：width,height,turn（当前玩家1/2）,winner（未结束=-1）,me（电脑玩家）。
        运算统一为 {"op":"运算名","args":[参数...]}; 不允许其他写法、公式字符串、函数或内嵌代码。
        二元整数运算 add/sub/mul/div/mod/min/max；比较 eq/ne/lt/le/gt/ge；布尔 and/or（短路）、not（一元）；if 有3参数且只执行选中分支。
        abs(n),len(数组),get(数组,索引),range(n)返回0..n-1,random(n)返回0..n-1；所有索引必须合法。
        cell(x,y) 返回格子值，越界返回-1；count(value) 返回这种格子的数量。
        fits(shape,x,y) 检查 [[dx,dy],...] 平移后全部在棋盘内且为空；rotate(shape) 返回顺时针旋转后的局部坐标；translate(shape,dx,dy) 平移坐标。
        line(x,y,value) 返回经过该格子的最长连续同色连线，检查横竖和两条对角线；fullRows() 返回全满行号数组。
        potential(player,target) 返回该玩家未被阻挡的 target 连线窗口的整数权重，可用于本地电脑评分（不是获胜判定）。
        指令只允许这些形式：
        {"set":"已声明变量","value":表达式}；{"if":布尔表达式,"then":[指令],"else":[指令]}（else可省略）；
        {"for":"新的局部变量","in":数组表达式,"do":[指令]}（有限遍历）；{"assert":布尔表达式}（false拒绝整个操作并回滚）；
        {"gridSet":[x,y,颜色]}；{"stamp":[shape,x,y,颜色]}；{"compact":true}（删除全部满行，上部下移、顶部补空）；
        {"next":true}（切换玩家）；{"finish":玩家编号或0}（0代表单人失败/双人平局，之后不再执行指令）；{"emit":"另一个事件名"}（禁止递归）。
        handlers.init 在重开时执行；handlers.tap 收到落子坐标；handlers.tick 根据 tickMs 定时执行；controls 的 id 对应其他 handlers 名称。
        多人落子规则必须检查空位，落子后检查连线获胜和满盘平局，再 next。默认五子棋15×15、五连胜，玩家1先手、电脑2；bot.evaluate 使用 me 计算自己优势减对手优势，不依赖 event 或 random。
        玩家和电脑共用同一个 handlers.tap，必须允许 turn=1 和 turn=2 都落子，颜色用当前 turn；绝对不要在 tap 中 assert turn==1 或 turn!=me，否则电脑无法操作。界面负责阻止电脑回合的人工点击。
        下落游戏必须定义形状数组、活动形状和位置、overlay；下落不能继续时 stamp 锁定，先计算 len(fullRows()) 得分，再 compact，最后生成新形状并检查出生碰撞。tickMs 建议600。
        spec 最大64KiB，节点最多4096，表达式深度最多12，事件最多32，每步计算最多20000操作；保持规则简洁。
        收到 rule_repair 事件时，依据上下文定位已有 surfaceId/componentId，修复其 spec；不要删除旧游戏或另建无关游戏，客户端会要求用户确认重开。

        以下是规则数据样例，不是仅有的两种游戏。用户要求变体时改变尺寸、形状、target、计分或事件规则即可。
        连线棋 spec：${RuleExamples.connection()}
        下落方块 spec：${RuleExamples.falling()}
    """.trimIndent()
}
