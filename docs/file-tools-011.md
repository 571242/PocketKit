# 文件工具（F01 重复文件 / F02 批量改名）实现记录

> 状态：代码与宿主测试完成，**未经真机验证**。本文只写跑过命令验证过的事实。
> 归档范围：`FileToolsActivity.java`、`cn.xianhua.pocketkit.filetools` 包、
> `app/src/test/java/cn/xianhua/pocketkit/filetools`、本文、`.scratch/file-tools-cleanup`。
> 集成已完成：根包入口桥接 filetools 实现，已接入 Manifest、工具注册与管理器。`manager_uris` 是内部原位置传递列表，避免通过缓存副本做删除或改名。

## 1. 到什么程度

| 编号 | 功能 | 实现 |
| --- | --- | --- |
| F01 | 重复文件查找 | 授权位置内递归列出 → 先按大小分桶 → 同大小的流式算 SHA-256 → 分组 → 勾选删除 |
| F02 | 批量改名 | 查找替换 / 前缀 / 后缀 / 序号（补零、可前置）+ 保留扩展名，完整预览后逐项执行 |

两个编号共用一个入口 Activity，用 `tool_id` 分派。

## 2. Intent 契约（给调用方）

目标组件 `cn.xianhua.pocketkit.FileToolsActivity`。lead 已注册（本模块不改这些文件）：

```xml
<activity android:name=".FileToolsActivity" android:exported="false"
          android:windowSoftInputMode="adjustResize" />
```

`selected-tools.json` 里已有 `F01` → `FileToolsActivity`（重复文件查找）、
`F02` → `FileToolsActivity`（批量重命名），`ToolCatalog` 已把两个编号放进「文件管理」组。

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `tool_id` | String | `"F01"` 重复文件（缺省值），`"F02"` 批量改名 |
| `title` | String | 可选；缺省时按 `tool_id` 给中文标题 |
| `getData()` | Uri | `content://` 或 `file://`；可以是单个文件，也可以是目录（含 document tree） |
| `EXTRA_STREAM` | Uri 或 `ArrayList<Uri>` | 与 `getData()` 同时生效，多选分享走这条 |
| `getClipData()` | ClipData | 剪贴板式多选也读，逐项取 Uri |

授权标志：读要 `FLAG_GRANT_READ_URI_PERMISSION`；F02 要真正改名还需要
`FLAG_GRANT_WRITE_URI_PERMISSION`。目录树带 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` 时会
`takePersistableUriPermission`，失败**不阻断本次使用**，只在界面上说明"重启后要重新选择"。

**收到位置后不再让用户重选**：`onCreate` 里直接把 Uri 交给面板。
F01 会立刻开始列文件与算摘要（外部已经授权过位置，再让人点一次"开始"等于把操作丢回去重做）；
F02 只把文件列出来，因为改名的规则必须由人来填。
只在首次创建时采纳 `getIntent()`——旋转后 Intent 还是那一份，再采纳一次会把用户已勾好的选择冲掉。

`file://` 的边界：只在共享存储内接受。指向 `context.getDataDir()` / `cacheDir` / `filesDir` /
`noBackupFilesDir` / `getExternalFilesDirs()` 之下任一前缀的路径一律拒绝并说明原因，
外部应用不能借这个入口读写本应用私有文件。

`supports(String)` / `limitation(String)` 是静态反射契约，和 `MediaToolsActivity`、
`DeviceToolsActivity` 同一形状；`ToolRegistry` 反射调用它们来判定编号是否可用。

## 3. F01 重复文件

### 3.1 判定

1. 0 字节文件排除并计数——空文件内容当然相同，但把空文件当重复删掉是误删。
2. 按 `size` 分桶；桶内只有一项的直接判为不重复，不读内容。
3. 桶内有 ≥2 项的才流式算 SHA-256，每 64 KiB 检查一次取消。
4. 摘要相同的成组，组内按显示路径排序；组间按"能省多少"从大到小排。

### 3.2 读不出来就是读不出来

任何一项抛异常（权限、虚拟文档不支持读、云端超时）都进 `Result.unreadable` 并记原因，
界面单独列出并写明：**这些文件没有参与比较，所以不能据此说它们不重复**。
存在失败项时结果里绝不会出现"没有重复"这种结论。

### 3.3 删除的四种保护

- **默认一个都不勾**：扫完 `checked` 是空集合，按钮点了会提示"没有勾选任何项"。
- **每组至少留一份**：`DuplicateScan.selectForDeletion` 是唯一判定点，整组全勾也只删前面
  `size()-1` 项，最后一项必留。
- **删前核验**：排除同一文档的授权别名及相同设备/inode 的同一文件或硬链接；无法核验独立身份时跳过。重新核对元数据，并且始终重算待删文件与保留副本的 SHA-256，二者均与扫描摘要一致才删除。外部并发写入不受本应用锁定，执行期间请勿修改这些文件。
- **确认弹窗**：列出前 12 个待删项和总数，说明"删除后无法恢复"。

界面一次最多铺 400 个勾选框；超出部分在结果里明确写"未铺出的项不会被删除，请用更小的目录
分批处理"——不假装全都铺上了。

### 3.4 读不到的目录

子目录读不到（没授权、云端拒绝、超出 32 层）时**记下来并继续**，不整次放弃；界面在结果里列出
"跳过了 N 个位置，这些地方没有参与比较"，并且在这种不完整的情况下**不会**说"没有发现重复"。
只有用户自己选的那个根目录读不到才是致命的，会直接报错。符号链接不跟随，也进这份跳过清单。

## 4. F02 批量改名

### 4.1 规则顺序

主名（`splitExtension` 切出的部分）依次经过：查找替换 → 序号（可前置）→ 前缀 → 后缀，
最后把扩展名接回去。`keepExtension=false` 时整个名字都参与替换，扩展名可以被改。
`.hidden` 这种点开头没有扩展名，`.` 只当作名字的一部分。

### 4.2 预览与拦截

必须先「预览」。`RenameRules.plan` 做三项检查，任意一项失败即整批拦下（`Plan.blockers` 非空，
执行按钮拒跑）：

| 检查 | 说明 |
| --- | --- |
| 非法名 | 空、`.`、`..`、含 `/` 或 `\`、控制字符、只有空格、UTF-8 超过 255 字节 |
| 批次内重名 | 大小写不敏感；多个源项映射到同一个目标名 |
| 与同目录已有文件重名 | 大小写不敏感；跳过自己的旧名（否则 `A.txt → a.txt` 会被自己拦住） |

同目录列表拿不到时（单独授权的文档通常不允许列父目录）会阻止执行，要求先授权真实父目录。执行每项前再次检查现有名字，发现同名立即拒绝，不依赖提供器替我们决定是否覆盖。

### 4.3 执行

`RenameRules.run` 逐项调用注入的操作，逐项记 `newId` / `failure`：

- **用提供器实际返回的 URI** 继续：`ManagerStore.rename` 内部会把 `n.uri` 换成
  `DocumentsContract.renameDocument` 的返回值（本地则是新 `file://`），执行完列表按新 URI 重建。
- **不是原子操作，也不冒充**：某项失败时其余项照常尝试，结果里逐项列出"旧名 → 新名"或
  "旧名 — 原因"，末尾统一说"失败的项保持原名"。
- **取消**：已完成的保持完成，未做的标"已取消，未执行"。
- 名字没变的项不调用重命名接口。

## 5. 失败与生命周期

- 所有后台结果都经 `Host.post` 回主线程，回调里先看 `alive()`（`!isFinishing() && !isDestroyed()`）。
  页面已销毁时**只更新状态对象，不碰任何 View**。
- `Host.retain` / `retain` 用 `onRetainNonConfigurationInstance` 保住一个纯数据 State：
  已选位置、扫描结果、勾选集、已填规则、当前摘要文本。不存 View、Bitmap、Activity 引用。
- 旋转后如果把一个正在跑的任务带过来，新页面会 `cancel()` 它并明说"上次的后台操作因页面重建而
  中断，请重新开始"——因为旧页面的 `workers` 已被 `shutdownNow`，那次任务不会再有结果。
- `Cancel` 同时看自己的开关和线程中断标志。
- 目录遍历上限：32 层、20000 个文件，超出即报错而不是静默截断。符号链接不跟随。
- 读不到的子目录/文件进"已跳过"清单并显示，但整次扫描继续；用户选的根目录读不到才直接失败。

## 6. 已验证

```
JDK  F:/mlbuild/tools/jdk-17.0.20.1+1
```

1. **纯逻辑测试（真断言 + 临时文件）** —— 本次增加保留副本摘要与文件身份回归，执行结果见本版发布验证记录：

```
javac -encoding UTF-8 -Xlint:all -d .scratch/file-tools-cleanup/core \
  app/src/main/java/cn/xianhua/pocketkit/filetools/{Cancel,DuplicateScan,RenameRules}.java \
  app/src/test/java/cn/xianhua/pocketkit/filetools/FileToolsCoreTest.java
java -ea -cp .scratch/file-tools-cleanup/core cn.xianhua.pocketkit.filetools.FileToolsCoreTest \
  .scratch/file-tools-cleanup/work
# → 82 assertions passed
```

测试开头会检查 `desiredAssertionStatus()`：没带 `-ea` 时直接抛异常，避免断言被悄悄跳过而"通过"。
覆盖：大小分桶、真实文件的重复分组、读失败上报、取消后保留部分结果、每组留一份、删前核验、
SHA-256 标准向量（`abc`）、扩展名切分、六种命名规则、11 种非法名（含 255 字节边界）、
批次内重名、非法名拦截、目录不可列时的告警、逐项失败、取消不执行、以及一次**真的在临时目录里
落地改名**。

2. **局部 javac 编译**（`-cp android-36/android.jar`，只编本模块及其依赖，不跑全局 Gradle）：

```
app/src/main/java/cn/xianhua/pocketkit/{FileToolsActivity,BaseToolActivity,ToolUi,ManagerStore,ManagerAlgorithms,ManagerShareProvider,core/SizeFmt}.java
app/src/main/java/cn/xianhua/pocketkit/filetools/*.java
# → 0 error（仅剩既有代码的 deprecation 提示）
```

两条命令都写在 `.scratch/file-tools-cleanup/check.sh`，一条 `./.scratch/file-tools-cleanup/check.sh`
即可复现上面两步。

## 7. 真实限制（未验证 / 做不到）

- **未经真机验证**。SAF 提供器（尤其云盘、Termux、SD 卡）的真实行为、`renameDocument` 在
  各家提供器上的返回值差异、`FLAG_SUPPORTS_RENAME` 缺失时的报错文案，全部只按 AOSP 文档实现。
- 重复查找**只扫你选中的目录或文件**，不扫全机。全盘扫描另外涉及 MANAGE_EXTERNAL_STORAGE
  与 Android/data 限制，本工具不碰。
- 云盘提供器要逐个下载才能算摘要，大目录会很慢且吃流量；界面只能显示进度，无法预估剩余时间。
- 不支持随机读取的虚拟文档（纯远程流）会在计算摘要时失败，进"读取失败"列表。
- 改名依赖提供器实现：不支持重命名的提供器会逐项失败并说明原因，**不会退化成"复制一份冒充改名"**。
- 与同目录已有文件的重名检查，在只能拿到单个文档授权时做不了，界面会明确告警；此时只剩系统层
  的最后一道拒绝。
- 界面最多铺 400 个勾选框；更多项请分目录处理。
- 「序号的补零位数」上限 9 位；序号起始必须是非负整数。
