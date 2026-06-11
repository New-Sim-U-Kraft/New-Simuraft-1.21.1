# SimuKraft 商业商店 JSON 自定义教程

本文面向整合包作者，说明如何通过外部 JSON 定义商业建筑的 NPC 交易内容。商业控制盒只负责雇佣、营业状态和管理；玩家右键商业员工 NPC 时打开的购买界面会读取这里的商业 JSON，并按村民交易风格显示。

## 文件位置

开发环境中的外部商业 JSON 放在：

```text
run/simukraftbuilding/commercial/
```

整合包实际发布时，对应游戏目录是：

```text
<gameDir>/simukraftbuilding/commercial/
```

推荐让商业 JSON 与建筑 `.sk` 元数据同名：

```text
breadShop.sk
breadShop.nbt
breadShop.json
```

也可以在 `.sk` 文件中显式指定商业 JSON：

```text
commercial:custom_bakery.json
```

加载优先级：

1. `.sk` 里的 `commercial:<file>.json`
2. 与 `.sk` 同名的外部 JSON
3. 模组内置建筑目录里的同名 JSON，例如 `assets/simukraft/building/commercial/<building>.json`

JSON 请保存为 UTF-8。Windows PowerShell 校验中文 JSON 时建议显式加 `-Encoding UTF8`。

## 顶层结构

商业 JSON 只需要定义商店名称、职业和报价列表：

```json
{
  "id": "breadShop",
  "name": "面包店",
  "job": {
    "id": "bread_shop_owner",
    "name": "面包店老板",
    "heldItem": "minecraft:bread"
  },
  "offers": []
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 建议 | 商业定义 ID，建议与建筑文件名一致 |
| `name` | 是 | 界面显示的商店名 |
| `job.id` | 是 | 商业员工写入市民数据的职业键 |
| `job.name` | 是 | 玩家看到的职业名 |
| `job.heldItem` | 否 | 商业员工默认手持物品 ID |
| `offers` | 是 | 交易报价列表，不能为空 |

新版 JSON 请使用 `offers`。旧版字段 `buildingId`、`shopMode`、`trades`、`buyTrades`、`sellPrice`、`buyPrice`、`materials`、`messages` 只作为兼容读取入口保留，新配置不要继续使用。

## 报价结构

每条报价都是 `cost -> result`：

```json
{
  "id": "shop_sells_bread",
  "visibleTo": "mixed",
  "cost": [{ "money": 0.25 }],
  "result": [{ "item": "minecraft:bread", "count": 1 }],
  "stock": {
    "item": "minecraft:bread",
    "max": 64,
    "materials": [
      { "item": "minecraft:wheat", "count": 3 }
    ]
  }
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 报价 ID，同一个 JSON 内必须唯一 |
| `visibleTo` | 否 | `player`、`npc`、`mixed`，默认 `player` |
| `cost` | 是 | 交易成本，最多 4 项 |
| `result` | 是 | 交易产出，最多 4 项 |
| `stock` | 建议 | 该报价关联的库存物品规则 |

资源写法：

```json
{ "money": 20.0 }
```

```json
{ "item": "minecraft:oak_log", "count": 64 }
```

规则：

- `money` 必须大于 0。
- `item` 必须是已注册物品 ID。不要写只有模型但没有注册的物品。
- `count` 最小为 1；省略时按 1 处理。
- 一条报价的 `cost` 和 `result` 都不能为空。

## 三个交易 Tab

界面左侧的 Tab 不需要在 JSON 里写字段，系统会按 `cost` 和 `result` 自动归类：

| Tab | JSON 形态 | 含义 |
| --- | --- | --- |
| 出售 | `cost` 有资金，`result` 有物品 | 商店向玩家出售物品 |
| 收购 | `cost` 有物品，`result` 有资金 | 商店收购玩家物品 |
| 交换 | `cost` 和 `result` 都是物品，且没有资金 | 以物换物 |

示例：

```json
{
  "id": "shop_buys_wheat",
  "visibleTo": "player",
  "cost": [{ "item": "minecraft:wheat", "count": 16 }],
  "result": [{ "money": 3.20 }],
  "stock": { "item": "minecraft:wheat", "max": 256 }
}
```

```json
{
  "id": "exchange_wheat_for_bread",
  "visibleTo": "player",
  "cost": [{ "item": "minecraft:wheat", "count": 3 }],
  "result": [{ "item": "minecraft:bread", "count": 1 }],
  "stock": { "item": "minecraft:bread", "max": 64 }
}
```

## visibleTo

`visibleTo` 决定报价给谁使用：

| 值 | 玩家界面可见 | NPC 自动经营可用 |
| --- | --- | --- |
| `player` | 是 | 否 |
| `npc` | 否 | 是 |
| `mixed` | 是 | 是 |

通常建议：

- 商店出售给玩家的商品用 `mixed`，这样 NPC 自动经营也能产生营业收入。
- 商店收购玩家物品用 `player`，避免 NPC 自动经营持续消耗城市资金。
- 以物换物一般用 `player`。

## NPC 自动买饭

饥饿 NPC 会通过 `CommercialFoodMarketService` 从本城市正在营业、已有员工的商业控制箱中寻找可购买食物。这个流程用于自动补饱食度，不等同于玩家打开交易界面。

会被当作食物报价的条件：

- `visibleTo` 必须是 `npc` 或 `mixed`。
- `cost` 只能包含资金，不能包含玩家/NPC 物品成本。
- `result` 必须包含至少一种有原版/模组 `FoodProperties` 的可食用物品，且不能包含资金。
- 商业箱库存或 `stock.materials` 供应必须足够。
- 商店必须在 `workTime` 营业时间内，并且控制箱处于运行状态。

执行结果：

- 成交会消耗对应库存或原材料，但不会从 NPC 背包扣物品。
- NPC 饱食度写入实体 `Hunger` NBT，不写入 `CitizenData` 或 SQLite 市民表。
- 系统会按报价资金的 40% 作为 `commercial_npc_food_tax` 存入城市财政流水。
- 找不到可购买食物时，NPC 会进入“太饿了，等待商店供餐”的临时状态，后续定期重试。

配置建议：

- 面包、熟肉、熟鱼这类基础食物可以使用 `visibleTo: "mixed"`，玩家和 NPC 都能消费。
- 不希望 NPC 自动吃掉的特殊食物使用 `visibleTo: "player"`。
- 如果使用 `stock.materials`，请把原材料放在商业控制盒附近容器；NPC 自动买饭不会从玩家背包取材料。

## 库存规则

`stock` 控制某个物品的库存：

```json
{
  "item": "minecraft:cake",
  "max": 16,
  "initial": 8,
  "restockAmount": 4,
  "restockInterval": 12000
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `item` | 库存物品 ID |
| `max` | 最大库存 |
| `initial` | 首次创建商业箱库存时的初始库存 |
| `restockAmount` | 每次补货增加数量 |
| `restockInterval` | 补货间隔，单位为服务器运行 tick |
| `materials` | 出售该库存物品时要从附近容器扣除的原材料 |

库存有两种模式。

普通 SQLite 库存：

```json
{
  "item": "minecraft:cake",
  "max": 16,
  "initial": 8,
  "restockAmount": 4,
  "restockInterval": 12000
}
```

这种模式适合不需要真实原材料的商品。库存写入 SQLite，按 `restockInterval` 自动补货。

材料供给型库存：

```json
{
  "item": "minecraft:bread",
  "max": 64,
  "materials": [
    { "item": "minecraft:wheat", "count": 3 }
  ]
}
```

这种模式适合面包、熟肉、熟鱼等需要真实原材料的商品。玩家购买和 NPC 自动经营都会先检查商业控制盒附近容器，材料足够才允许交易，并在成交时扣除对应材料。`materials` 非空时，该 `stock` 不再使用 SQLite 自动补货，`initial/restockAmount/restockInterval` 会被忽略。

通用规则：

- `cost` 里的物品会进入商店库存。
- `result` 里的物品会离开商店库存。
- 资金写在 `cost` 时会扣玩家所属城市资金；资金写在 `result` 时会存入玩家所属城市资金。
- 补货按 `level.getGameTime()` 计算服务器运行 tick 间隔，不受 `/time set` 影响。
- 同一个物品如果出现在多条报价里，库存初始化使用第一次遇到的 `stock` 规则。建议把带 `initial/restockAmount/restockInterval` 的出售报价放在前面。
- 如果某个交易物品完全没有任何 `stock` 规则，它不会被库存限制，也不会写入商业库存。商店出售和以物换物产出的物品必须配置库存。
- 原材料只会从商业控制盒附近容器扣除，不会从玩家背包扣除；玩家背包只处理报价 `cost` 中明确写出的物品。

修改 `max` 会同步到已有商业箱库存记录；修改 `initial` 不会重置已有库存。如果需要重置存档里的库存，需要删除对应商业控制盒或清理对应 SQLite 记录。

## 完整示例

```json
{
  "id": "custom_bakery",
  "name": "自定义面包店",
  "job": {
    "id": "custom_baker",
    "name": "面包师",
    "heldItem": "minecraft:bread"
  },
  "offers": [
    {
      "id": "shop_sells_bread",
      "visibleTo": "mixed",
      "cost": [{ "money": 0.25 }],
      "result": [{ "item": "minecraft:bread", "count": 1 }],
      "stock": {
        "item": "minecraft:bread",
        "max": 64,
        "materials": [
          { "item": "minecraft:wheat", "count": 3 }
        ]
      }
    },
    {
      "id": "shop_sells_cake",
      "visibleTo": "player",
      "cost": [{ "money": 3.00 }],
      "result": [{ "item": "minecraft:cake", "count": 1 }],
      "stock": {
        "item": "minecraft:cake",
        "max": 16,
        "initial": 8,
        "restockAmount": 4,
        "restockInterval": 12000
      }
    },
    {
      "id": "shop_buys_wheat",
      "visibleTo": "player",
      "cost": [{ "item": "minecraft:wheat", "count": 16 }],
      "result": [{ "money": 3.20 }],
      "stock": {
        "item": "minecraft:wheat",
        "max": 256
      }
    },
    {
      "id": "exchange_wheat_for_bread",
      "visibleTo": "player",
      "cost": [{ "item": "minecraft:wheat", "count": 3 }],
      "result": [{ "item": "minecraft:bread", "count": 1 }],
      "stock": {
        "item": "minecraft:bread",
        "max": 64
      }
    }
  ]
}
```

## 校验建议

只检查 JSON 语法：

```powershell
Get-ChildItem .\simukraftbuilding\commercial -Filter *.json |
  ForEach-Object { Get-Content -Raw -Encoding UTF8 $_.FullName | ConvertFrom-Json | Out-Null }
```

开发环境校验内置与运行目录：

```powershell
Get-ChildItem .\run\simukraftbuilding\commercial,.\src\main\resources\assets\simukraft\building\commercial -Filter *.json |
  ForEach-Object { Get-Content -Raw -Encoding UTF8 $_.FullName | ConvertFrom-Json | Out-Null }
```

常见问题：

| 现象 | 排查 |
| --- | --- |
| 控制盒显示定义无效 | `offers` 是否为空、物品 ID 是否真实注册、资金是否大于 0 |
| 某个 Tab 没有商品 | 报价形态是否符合出售/收购/交换归类规则 |
| 玩家能无限拿某物品 | 该 `result` 物品是否至少有一条 `stock` 规则 |
| 玩家卖出物品后库存不增加 | 该 `cost` 物品是否有 `stock` 规则 |
| NPC 自动经营没有处理报价 | `visibleTo` 是否为 `npc` 或 `mixed` |
| 玩家或 NPC 买东西不扣原材料 | 对应出售报价的 `stock.materials` 是否存在，材料是否放在商业控制盒附近容器 |
| 写了 `materials` 但自动补货不生效 | `materials` 非空会切换到材料供给型库存，不再使用 `restockAmount/restockInterval` |
| 中文 JSON 在 PowerShell 中校验失败 | 命令是否使用 `-Encoding UTF8` |

## 旧版价格同步规则

内置商业 JSON 的商品价格必须以旧版 `sellPrice` / `buyPrice` 为来源，并按旧版 `retail` 语义解释数量：

- `trades` 中 `retail: true` 表示零售，`sellPrice` 是单个物品价格，新版 `result.count` 通常为 1。
- `trades` 中缺省 `retail` 或 `retail: false` 表示按组出售，`sellPrice` 是一组 64 个物品的价格，新版应写成 `result.count: 64` 且 `cost.money` 直接等于旧版 `sellPrice`。
- `buyTrades` 按旧版收购界面以组为单位，`buyPrice` 是一组 64 个物品的价格，新版应写成 `cost.count: 64` 且 `result.money` 直接等于旧版 `buyPrice`。
- 旧版没有 `sellPrice` / `buyPrice` 的新增兑换项，不应声明为旧版价格来源。

已按该规则校准：建材店 `JCSD` 的 64 个建材交易、伐木工小屋 `lumberjacksHome` 的木头/树苗组交易，以及零售食品店铺的单个物品价格。
