# SimuKraft 工业系统自定义说明

本文面向整合包开发者，说明如何通过外部 JSON 定义工业建筑的职业、配方、容器、站位点和 NPC 工作流程。

## 文件位置

工业 JSON 放在：

```text
simukraftbuilding/industry/
```

推荐让工业 JSON 与建筑 `.sk` 文件同名：

```text
mill.sk
mill.json
```

也可以在 `.sk` 文件中显式指定工业配置：

```text
industrial:custom_mill.json
```

加载优先级：

1. `.sk` 里的 `industrial:<file>.json`
2. 与 `.sk` 同名的 JSON
3. 模组内置的备用 JSON

## 核心原则

工业流程完全由 JSON 编排，代码只负责安全执行。

`jobType` 和 `jobName` 不需要写进语言文件，也不要在代码里硬编码。系统会从工业 JSON 读取：

```json
{
  "jobType": "miller",
  "jobName": "磨坊工人"
}
```

`jobType` 用作数据库里的细分职业键，建议使用稳定的小写下划线命名，例如 `brick_maker`、`cow_farmer`。  
`jobName` 是界面上显示给玩家看的职业名称，可以直接写中文。

兼容键名：

```text
jobType / JobType / job_type
jobName / JobName / job_name
```

## 坐标规则

工业 JSON 里的坐标是结构坐标，不是世界坐标。

结构坐标指建筑 `.sk` 原始结构里的局部坐标：

```json
{ "pos": [4, 1, 6] }
```

建筑放置到世界后，系统会根据建筑朝向自动旋转并转换成世界坐标。所有点位和容器坐标必须落在已建成建筑范围内，否则会被忽略。

支持单点和数组：

```json
{
  "pos": [4, 1, 6]
}
```

```json
{
  "positions": [
    [2, 1, 4],
    [3, 1, 4]
  ]
}
```

数组最多读取 64 个坐标。

## 顶层字段

```json
{
  "id": "simukraft:mill",
  "name": "磨坊",
  "jobType": "miller",
  "jobName": "磨坊工人",
  "heldItem": "minecraft:wheat",
  "points": {},
  "containers": {},
  "recipes": [],
  "spawnEntity": {}
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 建议 | 工业定义 ID，建议唯一 |
| `name` | 是 | 工业流程名称，显示在工业控制箱 |
| `jobType` | 是 | 细分职业键，写入市民数据库 |
| `jobName` | 是 | 玩家看到的职业名 |
| `heldItem` | 否 | 默认手持物，配方或步骤可覆盖 |
| `points` | 是 | NPC 站位点、朝向点、机器点 |
| `containers` | 是 | 输入和输出容器位置 |
| `recipes` | 是 | 可选择的工业配方 |
| `spawnEntity` | 否 | 首次运行时生成动物等实体 |

## points 工作点

`points` 用来定义 NPC 移动和朝向目标。

```json
{
  "points": {
    "stand": {
      "type": "structure_pos",
      "positions": [[4, 1, 5], [5, 1, 5]],
      "select": "nearest"
    },
    "machine": {
      "type": "structure_pos",
      "pos": [4, 1, 6]
    }
  }
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `type` | 当前支持 `structure_pos` |
| `pos` | 单个结构坐标 |
| `positions` | 多个结构坐标 |
| `select` | `nearest` 或 `ordered` |

`nearest` 会选择距离 NPC 最近的可用点。  
`ordered` 会固定使用数组中的第一个点。

## containers 容器

`containers` 用来定义输入箱和输出箱。

```json
{
  "containers": {
    "input": {
      "type": "structure_pos",
      "positions": [[2, 1, 4], [3, 1, 4]]
    },
    "output": {
      "type": "structure_pos",
      "positions": [[6, 1, 4], [7, 1, 4]]
    }
  }
}
```

规则：

- 当前只支持 `structure_pos`。
- 容器必须是可访问的箱子、桶或兼容容器。
- 多个输入容器会按数组顺序统计和消耗。
- 多个输出容器会按数组顺序尝试插入。
- 普通插入会先模拟输出空间；`craft_recipe` / `craft_available_recipe` 会按消耗输入后的容器状态检查输出空间，空间不足会回滚本步骤消耗的材料。
- 不会扫描 3x3 范围，只访问 JSON 精确指定的坐标。
- `move_to_container` 到达判定按容器方块包围盒计算；NPC 靠近数组中任意一个目标容器即可进入下一步。
- 多容器输出建议至少给其中一个容器旁边留出可站立格；系统会优先走到可站立格，找不到时回退到容器中心目标。

## recipes 配方

每个工业 JSON 可以有多个配方，玩家在工业控制箱中选择。

```json
{
  "id": "wheat2cookie",
  "name": "曲奇生产",
  "heldItem": "minecraft:wheat",
  "inputs": [
    { "item": "minecraft:wheat", "count": 3 },
    { "item": "minecraft:sugar", "count": 1 }
  ],
  "outputs": [
    {
      "item": "minecraft:cookie",
      "baseAmount": 1,
      "randomRange": 2,
      "probability": 1.0
    }
  ],
  "steps": []
}
```

配方字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 配方 ID |
| `name` | 是 | 配方显示名称 |
| `heldItem` | 否 | 本配方默认手持物，优先级高于顶层 `heldItem` |
| `inputs` | 是 | 输入材料 |
| `outputs` | 是 | 输出产物 |
| `steps` | 是 | NPC 执行步骤 |

### inputs

```json
{ "item": "minecraft:dirt", "count": 4 }
```

支持字段：

| 字段 | 说明 |
| --- | --- |
| `item` | 物品 ID |
| `count` / `amount` | 数量，默认 1 |
| `consume` | 是否消耗，默认 `true` |
| `potion` | 药水类型，用于区分水瓶等药水物品 |

水瓶示例：

```json
{
  "item": "minecraft:potion",
  "potion": "minecraft:water",
  "count": 1
}
```

### outputs

```json
{
  "item": "minecraft:packed_mud",
  "baseAmount": 1,
  "randomRange": 0,
  "probability": 1.0,
  "ignoreMultiplier": true
}
```

支持字段：

| 字段 | 说明 |
| --- | --- |
| `item` | 物品 ID |
| `potion` | 药水类型 |
| `baseAmount` / `count` | 基础产量，默认 1 |
| `randomRange` | 额外随机数量范围，实际为 `0` 到 `randomRange - 1` |
| `probability` | 产出概率，范围 `0.0` 到 `1.0` |
| `ignoreMultiplier` | 是否忽略职业等级产量倍率，默认 `false` |

工业员工等级会提高产量倍率：每高 1 级约增加 5%。  
如果产物必须固定数量，例如空瓶返还，建议设置：

```json
{ "ignoreMultiplier": true }
```

## steps 工作步骤

步骤按数组顺序执行。最后一步完成后会回到第一步，继续循环生产。
单个配方最多读取 256 个步骤，超出部分不会执行；需要逐格、逐桶表现的工业可以拆成多个明确步骤。

支持动作：

| type | 作用 |
| --- | --- |
| `set_held_item` | 设置 NPC 手持物 |
| `repeat` / `loop` | 加载时展开一组重复步骤 |
| `move_to` | 移动到工作点 |
| `move_to_container` / `move_to_chest` | 移动到容器旁边 |
| `look_at` | 面朝某个点 |
| `look_at_container` / `look_at_chest` | 面朝指定容器 |
| `require_inputs` | 检查输入材料 |
| `require_output_space` | 检查输出空间 |
| `use_item` | 使用手持物，等待指定 tick |
| `craft_recipe` | 消耗一轮输入并写入输出 |
| `craft_available_recipe` / `craft_all_recipe` | 按当前可用输入批量合成 |
| `real_machine_recipe` | 把输入送入真实机器，等待机器产出 |
| `insert_item` / `store_item` / `put_item` | 向指定输出容器插入固定物品 |
| `fill_item` / `fill_slot` | 从输入容器补充指定物品到目标槽位 |
| `inspect_container` / `open_container` | 查看容器，打开后自然关闭 |
| `breed_entities` / `breed_animals` | 繁殖建筑范围内的动物 |
| `slaughter_entities` / `slaughter_animals` | 屠宰建筑范围内的成年动物 |
| `require_drops` / `require_drop_items` | 检查是否存在可收集掉落物 |
| `collect_drops` | 收集掉落物并写入输出容器 |
| `place_block` / `set_block` | 在结构坐标放置方块 |
| `place_fluid` / `place_liquid` | 在结构坐标放置液体 |
| `destroy_block` / `break_block` / `remove_block` | 摧毁结构坐标处的方块 |
| `require_block` / `wait_for_block` / `find_block` / `check_block` | 等待结构坐标中出现目标方块 |
| `set_status` | 设置工业箱状态文本 |

### set_held_item

```json
{
  "type": "set_held_item",
  "item": "minecraft:wheat"
}
```

如果不写 `item`，会使用配方 `heldItem`，再回退到顶层 `heldItem`。

### repeat / loop

```json
{
  "type": "repeat",
  "positions": [[3, 1, 4], [3, 1, 5]],
  "steps": [
    { "type": "move_to", "point": "pour_stand", "range": 1.2 },
    {
      "type": "place_fluid",
      "fluid": "simukraft:milk_fluid",
      "item": "minecraft:milk_bucket",
      "container": "input",
      "consume": true
    },
    { "type": "move_to_container", "container": "output", "range": 1.2 },
    { "type": "insert_item", "container": "output", "item": "minecraft:bucket", "count": 1 }
  ]
}
```

`repeat` 会在加载 JSON 时展开成普通线性步骤，适合把“对一组坐标逐格执行同一套动作”的长流程写短。  
如果 `repeat` 写了 `positions`，系统会按数组顺序循环。循环内的步骤如果没有自己的 `pos` 或 `positions`，会自动使用当前循环坐标。

字段说明：
| 字段 | 说明 |
| --- | --- |
| `positions` | 要依次处理的结构坐标数组 |
| `count` | 没有 `positions` 时重复执行几轮 |
| `steps` | 每轮要展开的一组普通步骤 |

注意：

- `repeat` 本身不会在运行时成为一个步骤；它只负责加载期展开。
- 展开后的总步骤数仍然受单配方 256 步上限限制。
- 嵌套步骤中显式写了 `pos` 或 `positions` 时，不会被循环坐标覆盖。

### move_to

```json
{
  "type": "move_to",
  "point": "stand",
  "range": 1.2
}
```

NPC 会走到 `points.stand`，距离目标小于 `range` 后进入下一步。

### move_to_container / move_to_chest

```json
{
  "type": "move_to_container",
  "container": "input",
  "range": 1.2
}
```

NPC 会优先走到指定容器旁边的可站立格，而不是走进容器方块本身。  
如果容器数组里有多个坐标，NPC 靠近任意一个目标容器的交互范围都会视为到达，适合玻璃厂这类多输入/多输出箱布局。
如果容器旁边没有可站立格，系统会回退到旧的容器中心目标，避免因为复杂装饰或箱子堆叠直接卡成“工作点不可站立”。
为了表现自然，推荐按下面顺序编排：

```json
{ "type": "move_to_container", "container": "output", "range": 1.2 },
{ "type": "look_at_container", "container": "output" },
{ "type": "inspect_container", "container": "output", "ticks": 20 }
```

容器字段优先级：

```text
container > input > output > 默认 input
```

### look_at

```json
{
  "type": "look_at",
  "point": "machine"
}
```

NPC 会面朝指定工作点。

### look_at_container / look_at_chest

```json
{
  "type": "look_at_container",
  "container": "output"
}
```

NPC 会面朝指定容器，通常配合 `move_to_container` 和 `inspect_container` 使用。

### require_inputs

```json
{
  "type": "require_inputs",
  "container": "input"
}
```

检查配方 `inputs` 是否足够。材料不足时暂停重试，不会消耗材料。

也可以写：

```json
{
  "type": "require_inputs",
  "input": "input"
}
```

优先级：`container` > `input` > 默认 `input`。

### require_output_space

```json
{
  "type": "require_output_space",
  "container": "output"
}
```

检查输出容器是否有足够空间。空间不足时暂停重试，不会消耗材料。

也可以写：

```json
{
  "type": "require_output_space",
  "output": "output"
}
```

优先级：`container` > `output` > 默认 `output`。

### use_item

```json
{
  "type": "use_item",
  "ticks": 80,
  "swing": true
}
```

NPC 会等待指定 tick。  
`swing: true` 时只在开始使用时挥手一次，表现更接近原版玩家使用工具。

### inspect_container / open_container

```json
{
  "type": "inspect_container",
  "container": "input",
  "ticks": 40
}
```

NPC 会查看指定容器，容器会打开，等待 `ticks` 后自然关闭。  
适合做“先看箱子、再取材料、再加工”的表现步骤。

如果需要 NPC 先走到箱子旁边，建议在前面接：

```json
{ "type": "move_to_container", "container": "input", "range": 1.2 },
{ "type": "look_at_container", "container": "input" }
```

容器字段优先级：

```text
container > input > 默认 input
```

### breed_entities / breed_animals

```json
{
  "type": "breed_entities",
  "entityType": "minecraft:cow",
  "container": "input",
  "count": 1,
  "requireFood": true
}
```

作用：

1. 在建筑范围内寻找指定类型的成年动物。
2. 每次繁殖需要两只可繁殖动物。
3. `requireFood: true` 时，从输入容器消耗对应动物的原版食物。
4. `requireFood: false` 时，不检查也不消耗食物。

字段说明：

| 字段 | 说明 |
| --- | --- |
| `entityType` / `entity` | 生物类型，例如 `minecraft:cow`；不写则匹配任意动物 |
| `container` / `input` | 食物所在容器，默认 `input` |
| `count` | 本步骤最多繁殖几对，默认 1 |
| `requireFood` / `requiresFood` | 是否需要并消耗对应食物，默认 `true` |
| `point` | 可选，限定在某个工作点附近 |
| `radius` | 配合 `point` 使用的搜索半径，默认 6 |

“对应食物”使用原版动物自己的 `isFood` 判断，例如牛和羊需要小麦，猪需要胡萝卜/马铃薯/甜菜根，鸡需要种子。
如果填写了无效的 `entityType`，系统会认为缺少目标生物，不会退化成匹配所有动物。

### slaughter_entities / slaughter_animals

```json
{
  "type": "slaughter_entities",
  "entityType": "minecraft:cow",
  "count": 1
}
```

NPC 会屠宰建筑范围内指定类型的成年动物。  
默认只屠宰成年动物，不屠宰幼年动物。掉落物会正常掉在世界里，通常后面接 `collect_drops`。

字段说明：

| 字段 | 说明 |
| --- | --- |
| `entityType` / `entity` | 生物类型；不写则匹配任意动物 |
| `count` | 本步骤最多屠宰几只，默认 1 |
| `point` | 可选，限定在某个工作点附近 |
| `radius` | 配合 `point` 使用的搜索半径，默认 6 |

如果填写了无效的 `entityType`，系统会认为缺少目标生物，不会退化成匹配所有动物。

### require_drops / require_drop_items

```json
{
  "type": "require_drops",
  "item": "minecraft:egg",
  "point": "machine",
  "radius": 8
}
```

检查建筑范围内是否有可收集掉落物，不会移动物品，也不会打开箱子。  
适合放在 `move_to_container`、`inspect_container` 和 `collect_drops` 前面，避免没有掉落物时 NPC 反复走到输出箱开箱。

字段说明：

| 字段 | 说明 |
| --- | --- |
| `item` | 可选，只检查指定物品；不写则检查范围内全部掉落物 |
| `point` | 可选，限定在某个工作点附近 |
| `radius` | 配合 `point` 使用的搜索半径，默认 6 |

### collect_drops

```json
{
  "type": "collect_drops",
  "output": "output",
  "item": "minecraft:beef"
}
```

NPC 会收集建筑范围内的掉落物，并插入输出容器。输出空间不足时不会删除掉落物，会等待重试。
如果没有可收集掉落物，本步骤会等待，不会被当作生产成功。

字段说明：

| 字段 | 说明 |
| --- | --- |
| `output` / `container` | 输出容器，默认 `output` |
| `item` | 可选，只收集指定物品；不写则收集范围内全部掉落物 |
| `count` | 最多处理几个掉落物实体；不写或为 0 表示不限制 |
| `point` | 可选，限定在某个工作点附近 |
| `radius` | 配合 `point` 使用的搜索半径，默认 6 |

### insert_item / store_item / put_item

```json
{
  "type": "insert_item",
  "container": "output",
  "item": "minecraft:bucket",
  "count": 1,
  "ticks": 12
}
```

NPC 会打开指定容器，等待一小段时间后插入固定物品并自然关闭容器。它不会检查或消耗输入材料，通常用于表现“已经拿在手里”的物品被放回箱子，例如倒完牛奶后放回空桶。

字段说明：

| 字段 | 说明 |
| --- | --- |
| `container` / `output` | 目标容器，默认 `output` |
| `item` | 要插入的物品 ID |
| `count` | 插入数量，默认 1 |
| `ticks` | 容器打开后等待多久再插入并关闭，默认 12 |

注意：

- `insert_item` 是直接向容器写入物品，建议只放在已经由前置步骤消耗材料或产生物品之后。
- 输出空间不足时会等待重试，不会继续推进后续步骤。
- 当前该动作只支持普通物品 ID，不支持 `potion` 细分。

### fill_item / fill_slot / refill_item / refill_slot

```json
{
  "type": "fill_item",
  "point": "furnace",
  "slot": 1,
  "item": "minecraft:coal",
  "input": "input",
  "targetCount": 64,
  "thresholdCount": 0,
  "swing": true
}
```

从输入容器取指定物品，补进目标方块的指定槽位。常见用法是在检测熔炉燃料槽或烧炼物槽数量低于 1 后，把燃料或原料补到 64。

执行规则：

1. 读取 `point` 对应机器或容器的 `slot` 槽。
2. 如果槽里是其他物品，等待重试，不会替换。
3. 如果设置了 `thresholdCount`，当前数量大于该值时直接跳过。
4. 计算距离 `targetCount` 还差多少。
5. 从 `input` 容器取对应物品，材料不足目标数量时有多少取多少。
6. 把取出的物品插入目标槽位。

字段说明：

| 字段 | 说明 |
| --- | --- |
| `point` | 目标机器或容器点位 |
| `slot` | 目标槽位编号，原版熔炉输入槽为 0，燃料槽为 1，输出槽为 2 |
| `item` | 要补充的物品 ID |
| `input` / `container` | 原材料来源容器，默认 `input` |
| `targetCount` / `target` / `fillTo` | 目标数量，默认 64 |
| `thresholdCount` / `threshold` | 可选阈值；例如 `0` 表示槽位空了才补 |
| `count` | 未写 `targetCount` 时也可作为目标数量 |
| `swing` | 成功执行时是否让 NPC 挥手 |

如果源容器没有该物品，会显示缺少输入材料并等待；如果目标槽没有空间或不接受该物品，会显示机器输入阻塞。

### craft_recipe

```json
{
  "type": "craft_recipe",
  "input": "input",
  "output": "output"
}
```

执行真正的生产：

1. 再次检查输入材料。
2. 预生成输出列表。
3. 消耗输入材料。
4. 按消耗后的容器状态检查输出空间。
5. 插入输出物品。
6. 增加工业职业经验。

如果输入或输出失败，会显示阻塞状态并等待重试；输出空间不足时会把本步骤刚消耗的输入放回。这样同一个箱子同时作为输入和输出时，消耗输入腾出的槽位也会被正确计算。

`craft_recipe` 默认使用整条配方的 `inputs` 和 `outputs`。如果这个步骤自己写了 `inputs` 或 `outputs`，则只在本步骤内覆盖对应列表，适合一条流程里先经过真实机器产出中间产物，再把中间产物继续加工。

### craft_available_recipe / craft_all_recipe

```json
{
  "type": "craft_available_recipe",
  "input": "input",
  "output": "output",
  "inputs": [
    { "item": "minecraft:iron_ingot", "count": 1 }
  ],
  "outputs": [
    { "item": "minecraft:iron_nugget", "baseAmount": 9, "ignoreMultiplier": true }
  ]
}
```

按输入容器里当前可消耗的材料数量批量执行同一个配方。  
例如步骤输入是 1 个铁锭，输出是 9 个铁粒；如果箱子里有 7 个铁锭，本步骤会一次消耗 7 个铁锭并输出 63 个铁粒。

规则：

1. 多个输入时，按所有输入共同允许的最大次数执行，例如 A 可做 5 次、B 可做 3 次，则本步骤做 3 次。
2. `consume: false` 的输入只检查是否存在，不会按次数重复消耗。
3. 输出空间不足时会回滚本步骤刚消耗的输入，不会吞材料。
4. 旧的 `craft_recipe` 仍然只执行一轮，适合需要固定节奏的配方。

### real_machine_recipe

```json
{
  "type": "real_machine_recipe",
  "point": "furnace",
  "input": "input",
  "output": "output",
  "outputPolicy": "extract_to_output",
  "timeoutTicks": 12000,
  "pollTicks": 20,
  "swing": true
}
```

把工业流程接到真实方块实体或机器上。它不会凭空生成 `outputs`，而是把 `inputs` 从输入容器取出并插入机器，然后等待机器自己的真实产物出现。

执行流程：

1. 解析 `point` 指向的机器方块位置。
2. 检查 `input` 容器材料是否足够。
3. 预检查机器输入槽是否能整批放下所有输入。
4. 消耗输入容器材料，并插入机器。
5. 记录 `machine_state`，保存步骤、机器位置、输出策略、开始时间、轮询间隔和输出基线。
6. 每隔 `pollTicks` 检查一次机器输出，相对基线达到 `recipe.outputs` 后完成。
7. 根据 `outputPolicy` 把真实产物搬到输出容器，或保留在机器中。

字段说明：

| 字段 | 说明 |
| --- | --- |
| `point` | 机器方块点位，必须是 `points` 里的 `structure_pos` |
| `input` / `container` | 输入容器，默认 `input` |
| `output` / `container` | 输出容器，默认 `output`；`keep_in_machine` 时可以不写 |
| `outputPolicy` / `output_policy` | 输出策略：`extract_to_output` 或 `keep_in_machine`，默认 `extract_to_output` |
| `timeoutTicks` / `timeout` | 超时时间，默认 12000 tick |
| `pollTicks` / `poll` | 轮询间隔，默认 20 tick |
| `swing` | 开始插入机器时是否让 NPC 挥手 |

输出策略：

- `extract_to_output`：机器产物达到配置后，从机器输出槽取出并插入 `output` 容器。输出容器满时会等待重试，不会删除机器产物。
- `keep_in_machine`：机器产物留在机器输出槽，工业步骤直接完成。

适配器规则：

- 默认只使用通用机器/容器适配器，不写死熔炉、高炉、烟熏炉等具体方块。
- 通用适配器优先通过 NeoForge `IItemHandler` 访问容器能力，拿不到时回退原版 `Container`。
- 复杂机器可以通过 `IndustrialMachineOperationService.registerAdapter` 注册适配器，或监听 `IndustrialMachineOperationEvent.Start` 替换本次操作的适配器。
- Hook 事件包括 `Start`、`Tick`、`Complete`、`Abort`，监听器可以接管轮询、标记完成或让步骤等待重试。

注意：

- `recipe.inputs` 在这里表示要真实插入机器的输入，不再只是虚拟合成材料。
- `recipe.outputs` 只用于完成检测和可提取产物过滤，不会直接生成物品。
- 如果 `real_machine_recipe` 步骤自己写了 `inputs` 或 `outputs`，会优先使用步骤里的列表；没写时才回退到整条配方的 `inputs` 和 `outputs`。
- 如果步骤显式写 `"inputs": []`，则不会向机器插入新输入，只等待并提取匹配的真实输出，适合配合 `fill_item` 先把机器槽位补满后循环取产物。
- 如果等待真实产物时发现前置 `fill_item` 指定的机器槽低于 `thresholdCount`，流程会清理本次等待状态并回退到补料步骤，避免玩家拿走中间物后一直卡住。
- 工业工人等级倍率不会复制真实机器产物，真实产量以机器实际输出为准。
- 真实机器步骤开始后，状态会写入工业箱的 `machine_state`。重启后会继续等待或提取，不会重复扣输入。
- 手动暂停、换配方、解雇工人、控制箱拆除、任务中断、建筑或工业定义变化会中止并清理 `machine_state`。

原版熔炉示例：

```json
{
  "id": "iron_ingot_real_furnace",
  "name": "真实熔炉烧铁",
  "heldItem": "minecraft:iron_ore",
  "inputs": [
    { "item": "minecraft:iron_ore", "count": 1 },
    { "item": "minecraft:coal", "count": 1 }
  ],
  "outputs": [
    { "item": "minecraft:iron_ingot", "baseAmount": 1, "ignoreMultiplier": true }
  ],
  "steps": [
    { "type": "move_to_container", "container": "input", "range": 1.2 },
    { "type": "look_at_container", "container": "input" },
    { "type": "inspect_container", "container": "input", "ticks": 20 },
    { "type": "set_held_item", "item": "minecraft:iron_ore" },
    { "type": "move_to", "point": "furnace_stand", "range": 1.2 },
    { "type": "look_at", "point": "furnace" },
    {
      "type": "fill_item",
      "point": "furnace",
      "slot": 0,
      "item": "minecraft:iron_ore",
      "input": "input",
      "targetCount": 64,
      "thresholdCount": 0,
      "swing": true
    },
    {
      "type": "fill_item",
      "point": "furnace",
      "slot": 1,
      "item": "minecraft:coal",
      "input": "input",
      "targetCount": 64,
      "thresholdCount": 0,
      "swing": true
    },
    {
      "type": "real_machine_recipe",
      "point": "furnace",
      "input": "input",
      "output": "output",
      "outputPolicy": "extract_to_output",
      "pollTicks": 20,
      "timeoutTicks": 12000,
      "swing": true,
      "inputs": [],
      "outputs": [
        { "item": "minecraft:iron_ingot", "baseAmount": 1, "ignoreMultiplier": true }
      ]
    }
  ]
}
```

### place_block / set_block

```json
{
  "type": "place_block",
  "point": "kiln_slot",
  "block": "minecraft:fire",
  "replace": true,
  "swing": true
}
```

NPC 会在指定结构坐标放置方块。目标已经是同类方块时直接进入下一步，不重复放置也不重复消耗材料。

字段说明：
| 字段 | 说明 |
| --- | --- |
| `block` | 要放置的方块 ID，例如 `minecraft:fire` |
| `point` | 使用 `points` 中的命名点；如果该点有多个坐标，会全部处理 |
| `pos` / `positions` | 直接在步骤中写结构坐标；优先级高于 `point` |
| `replace` | 是否允许覆盖非空气方块，默认 `false` |
| `consume` | 是否从输入容器消耗材料，默认 `false` |
| `container` / `input` | `consume: true` 时使用的材料容器，默认 `input` |
| `item` | `consume: true` 时消耗的物品；不写时默认使用 `block` 同名物品 |
| `swing` | 成功改变方块时是否挥手 |

### place_fluid / place_liquid

```json
{
  "type": "place_fluid",
  "pos": [4, 1, 6],
  "fluid": "minecraft:water",
  "replace": true,
  "swing": true
}
```

NPC 会在指定结构坐标放置液体。目标已经是同类液体时直接进入下一步。

字段说明：
| 字段 | 说明 |
| --- | --- |
| `fluid` / `liquid` | 要放置的液体 ID，例如 `minecraft:water` 或 `minecraft:lava` |
| `point` | 使用 `points` 中的命名点 |
| `pos` / `positions` | 直接在步骤中写结构坐标；优先级高于 `point` |
| `replace` | 是否允许覆盖非空气方块，默认 `false` |
| `consume` | 是否从输入容器消耗物品，默认 `false` |
| `item` | `consume: true` 时消耗的物品；不写时默认使用对应桶，例如水桶 |
| `container` / `input` | `consume: true` 时使用的材料容器，默认 `input` |
| `swing` | 成功改变方块时是否挥手 |

如果 `point` 或 `positions` 一次指定多个坐标，并且 `consume: true`，系统会按实际需要放置的格数消耗对应数量的物品。  
如果需要 NPC “每次倒一桶，回箱子放空桶，再拿下一桶”，建议把每个坐标拆成独立 `place_fluid` 步骤，并在中间加入 `move_to_container` 与 `insert_item`。

### destroy_block / break_block / remove_block

```json
{
  "type": "destroy_block",
  "positions": [[4, 1, 6], [5, 1, 6]],
  "dropItems": false,
  "swing": true
}
```

NPC 会摧毁指定结构坐标处的方块。目标已经是空气时直接进入下一步。默认不生成掉落物，避免工业流程刷物品。

字段说明：
| 字段 | 说明 |
| --- | --- |
| `point` | 使用 `points` 中的命名点 |
| `pos` / `positions` | 直接在步骤中写结构坐标；优先级高于 `point` |
| `dropItems` / `drop` | 是否生成原版掉落物，默认 `false` |
| `swing` | 成功摧毁方块时是否挥手 |

安全规则：

- 所有坐标都会按建筑旋转转换到世界坐标，且必须落在当前已建成建筑范围内。
- `destroy_block` 不会摧毁工业控制箱本身。
- `place_block` 和 `place_fluid` 在目标被阻挡且没有 `replace: true` 时会等待重试。
- 如果 `consume: true` 且材料不足，会显示缺少输入材料，不会放置方块或液体。

### require_block / wait_for_block / find_block / check_block

```json
{
  "type": "require_block",
  "pos": [14, 1, 5],
  "block": "simukraft:cheese_block",
  "statusText": "等待最后一格奶源凝固"
}
```

NPC 会等待指定结构坐标中出现目标方块。目标出现前会保持等待并定期重试，出现后进入下一步。  
适合监听生产过程中的世界状态，例如奶酪工厂最后一格奶源变成奶酪块后，再开始收集并放入箱子。

字段说明：
| 字段 | 说明 |
| --- | --- |
| `block` | 要等待的方块 ID |
| `point` | 使用 `points` 中的命名点；如果该点有多个坐标，会在这些坐标中查找 |
| `pos` / `positions` | 直接在步骤中写结构坐标；优先级高于 `point` |
| `count` | 多坐标时至少需要匹配几个目标方块，默认 1 |
| `statusText` | 等待时显示的状态细节，可选 |

如果需要确认整片区域都完成，可以把 `positions` 写成完整区域，并把 `count` 设为区域格数。奶酪工厂这类从左到右依次放置奶源的流程，也可以只监听最后一格。

### 动物农场步骤示例

```json
[
  { "type": "move_to_container", "container": "input", "range": 1.2 },
  { "type": "look_at_container", "container": "input" },
  { "type": "inspect_container", "container": "input", "ticks": 20 },
  { "type": "require_inputs", "container": "input" },
  { "type": "require_output_space", "container": "output" },
  { "type": "set_held_item", "item": "minecraft:wheat" },
  { "type": "move_to", "point": "stand", "range": 1.2 },
  { "type": "look_at", "point": "pen" },
  { "type": "use_item", "ticks": 30, "swing": true },
  { "type": "breed_entities", "entityType": "minecraft:cow", "container": "input", "count": 1, "requireFood": true },
  { "type": "set_held_item", "item": "minecraft:iron_sword" },
  { "type": "use_item", "ticks": 20, "swing": true },
  { "type": "slaughter_entities", "entityType": "minecraft:cow", "count": 1 },
  { "type": "require_drops", "point": "pen", "radius": 8 },
  { "type": "move_to_container", "container": "output", "range": 1.2 },
  { "type": "look_at_container", "container": "output" },
  { "type": "inspect_container", "container": "output", "ticks": 20 },
  { "type": "collect_drops", "output": "output" }
]
```

### set_status

```json
{
  "type": "set_status",
  "statusKey": "gui.simukraft.industrial.status.running",
  "statusText": "正在加工"
}
```

`statusKey` 可以使用已有语言 key。  
`statusText` 会直接拼在状态后面。

## spawnEntity 首次生成实体

适合动物农场等工业建筑。

```json
{
  "spawnEntity": {
    "enabled": true,
    "type": "minecraft:cow",
    "count": 4
  }
}
```

规则：

- 只在工业箱首次运行时执行一次。
- 如果建筑范围附近已有同类型实体，不会重复刷。
- 生成点会从工业控制箱附近随机挑选，必须在建筑范围内且位置可站立。
- 不会因为动物流失而无限补刷。

## 建筑完整性与补全建筑

工业控制箱界面会显示建筑完整性，并提供“显示边界”“拆除”和“补全建筑”操作。

完整性规则：

- 完整性只比较方块类型，不比较门开关、箱子开合等方块状态，避免正常交互被当作损坏。
- 建筑区块未完整加载时，完整性显示为未加载，不会执行自动拆除或补全判断。
- 服务器配置 `building_integrity.autoDemolishThresholdPercent` 控制自动拆除阈值，默认 `30`；设为 `0` 可关闭自动拆除。
- 自动拆除会走统一拆除流程，清理控制箱、POI、雇佣和建筑记录。

补全建筑规则：

- `building_integrity.repairMoneyPerBlock` 控制每个自动补全方块的城市资金花费，默认 `0.05`。
- 如果缺失方块不属于配置中需要材料的方块，点击“补全建筑”会直接按结构记录补回并扣城市资金。
- 如果缺失方块属于配置中需要材料的方块，只会提示剩余数量，需要玩家自己补齐；系统不会凭空生成这部分材料。
- 如果同一位置被其他建筑占用，不会被补全覆盖。

## 完整最小示例

```json
{
  "id": "simukraft:mill",
  "name": "磨坊",
  "jobType": "miller",
  "jobName": "磨坊工人",
  "heldItem": "minecraft:wheat",
  "points": {
    "stand": {
      "type": "structure_pos",
      "positions": [[4, 1, 5], [5, 1, 5]],
      "select": "nearest"
    },
    "machine": {
      "type": "structure_pos",
      "pos": [4, 1, 6]
    }
  },
  "containers": {
    "input": {
      "type": "structure_pos",
      "positions": [[2, 1, 4], [3, 1, 4]]
    },
    "output": {
      "type": "structure_pos",
      "positions": [[6, 1, 4], [7, 1, 4]]
    }
  },
  "recipes": [
    {
      "id": "wheat2cookie",
      "name": "曲奇生产",
      "inputs": [
        { "item": "minecraft:wheat", "count": 3 },
        { "item": "minecraft:sugar", "count": 1 }
      ],
      "outputs": [
        { "item": "minecraft:cookie", "baseAmount": 1, "randomRange": 2, "probability": 1.0 }
      ],
      "steps": [
        { "type": "move_to_container", "container": "input", "range": 1.2 },
        { "type": "look_at_container", "container": "input" },
        { "type": "inspect_container", "container": "input", "ticks": 20 },
        { "type": "require_inputs", "container": "input" },
        { "type": "require_output_space", "container": "output" },
        { "type": "set_held_item", "item": "minecraft:wheat" },
        { "type": "move_to", "point": "stand", "range": 1.2 },
        { "type": "look_at", "point": "machine" },
        { "type": "use_item", "ticks": 80, "swing": true },
        { "type": "set_held_item", "item": "minecraft:cookie" },
        { "type": "move_to_container", "container": "output", "range": 1.2 },
        { "type": "look_at_container", "container": "output" },
        { "type": "inspect_container", "container": "output", "ticks": 20 },
        { "type": "craft_recipe", "input": "input", "output": "output" }
      ]
    }
  ]
}
```

## 调试建议

- 在工业控制箱界面打开“显示边界”，检查建筑范围、容器点、站位点和机器点是否正确。
- 如果界面提示“工业流程配置无效”，优先检查 JSON 格式、`recipes` 是否为空、点位是否缺失。
- 如果 NPC 一直提示缺少输入材料，检查容器坐标是否是结构坐标，以及箱子里物品 ID 是否正确。
- 如果输出容器满，系统不会消耗输入；先清理输出箱再运行。
- 如果 NPC 一直卡在“前往 input/output”，检查目标容器数组里是否至少有一个箱子旁边能站人；多输出箱可以把最容易接近的箱子也加入 `positions`。
- 如果容器能存取物品但不会打开，确认步骤顺序里 `move_to_container` 后面接了 `look_at_container` 和 `inspect_container`。
- 如果旋转建筑后点位错位，说明 JSON 坐标不是原始结构坐标，需要回到 `.sk` 原始局部坐标重新标点。
- 修改 JSON 后重新进入世界或重新打开相关界面，确保最新配置被加载。
