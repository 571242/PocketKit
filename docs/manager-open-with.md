# 管理器“打开方式”定位原文件

## 根因

`ManagerActivity.handleIncoming()` 原先只校验 `ACTION_VIEW` / `ACTION_SEND(_MULTIPLE)` 的 URI 并调用 `ManagerStore.resolve()`，随后直接弹文件操作菜单。它没有解析或列出父目录，也没有单独的目标高亮状态。

普通 `content://` 授权通常只覆盖被发送的文件；文件名或 URI 字符串不能证明父目录，更不能替代目录读取授权。因此不能把文件复制到缓存或当前窗口后声称已定位原位置。

## 修改

- 单个 `ACTION_VIEW` 或 `ACTION_SEND`：先校验 URI 方案、临时读取授权和本地共享存储边界，再解析文件。
- `file://`：仅在共享存储内、非其他应用 `Android/data` / `Android/obb` 且目标实际可读时，列出真实父目录并精确匹配 canonical path。
- `content://`：
  - 外部存储文档提供器的 `primary:` documentId 仅在安全解析、映射后的父目录确实可列出时定位；
  - 带 tree grant 的文档 URI 可通过 provider 返回的 document path 尝试父目录；
  - 其他单文件 URI 不猜父路径。
- 定位成功：当前窗切到父目录，用独立星标和背景色高亮目标；不会把高亮混入多选集合。
- 定位失败：如实说明 Android 单文件授权限制，提供“授权父目录”和“文件详情”。用户选择目录后，只有 provider documentId 或 canonical 路径能证明目标是该目录的直接孩子时才接受。
- 多文件 `ACTION_SEND_MULTIPLE`：保留原有显式“复制收到的文件”流程，不将副本冒充原位置。
- `ManagerShareProvider` 未改：它只负责管理器向其他应用打开时的只读临时导出，与外部 URI 的原位置定位无关。

## Manifest 需求

定位入口是一个独立的 `activity-alias`，管理器本体的过滤器没有放宽：

- `.ManagerActivity`（本体）：`ACTION_SEND` / `ACTION_SEND_MULTIPLE` + `*/*`。它不是任何文件类型的查看器。
- `.ManagerLocateActivity`（别名，label「拾用·定位文件位置」，`targetActivity=".ManagerActivity"`，`exported="true"`）：
  - 第一个过滤器：`ACTION_VIEW` + `CATEGORY_DEFAULT` + `content`/`file` + `*/*`；
  - 第二个过滤器：`ACTION_VIEW` + `CATEGORY_DEFAULT` + `content`/`file`（无 MIME）。Android 不会拿不带 MIME 类型的 intent 去匹配声明了 `mimeType` 的过滤器，只用 `setData` 的发送方会落空，所以两个都要。
  - 故意不加 `android:priority`，不加 `BROWSABLE`，不重复 `SEND`。这样它只是「打开方式」列表里的普通一项，系统永远不会因为本应用把它设成默认处理器。

0.10.1 删掉的是本体上的 catch-all `ACTION_VIEW`（那会让管理器同时出现在所有应用的打开方式里却打不开文件）。现在的做法是把「定位」单独做成有清晰中文标签的入口，不再否决用户想要的那一项。

定位仍然要花一次点击：从别的应用「打开方式 → 拾用·定位文件位置」进来，或在管理器菜单里用「打开所在位置」。

管理器自己菜单里的「其他应用打开」会把定位入口从选择器里排除（`EXTRA_EXCLUDE_COMPONENTS`），因为那一路径交出去的是缓存副本，指向副本目录就会是一个假位置。

## 最小验证

已执行：

1. 独立 JVM `ManagerAlgorithmsTest.main`：**299 assertions PASS**，新增覆盖 `primary:` documentId 正常路径、非 primary 卷回退、绝对路径/反斜杠/空段/`.`/`..` 拒绝。
2. `ManagerOpenWithContractTest.main`：**28 assertions PASS**，覆盖别名入口（label / targetActivity / VIEW+DEFAULT / content+file / 无 priority / 无 SEND / 无 BROWSABLE）、单文件定位、高亮、授权/详情回退、不按名称猜测身份、`onNewIntent` 复用与排队、VIEW 标题，以及拒绝自家分享副本、不在自己的选择器里提供定位入口。
3. `ManagerPermissionSelfTest.main`：**25 checks PASS**；`RemotePanelContractTest`：**63 checks PASS**。
4. `aapt2 compile` + `aapt2 link`（build-tools 36.0.0，SDK 36 `android.jar`）通过，并 `aapt2 dump xmltree` 确认别名以 `exported=true`、`targetActivity=.ManagerActivity`、两个过滤器完整进入二进制清单。
5. 使用 JDK 17 + SDK 36 `android.jar` 定向编译 `ToolUi`、`BaseToolActivity`、`ManagerAlgorithms`、`ManagerStore`、`ManagerShareProvider`、`ManagerActivity`：**成功，无编译错误**；仅有既有弃用 API 提示。
6. `tools/verify-manager-release.py` 端到端通过（含新的别名断言）。

未运行整包 Gradle、APK、模拟器或真机，按协作约定留给 lead。真机至少验证：

- 从图库/文件管理器对一张照片、一个 PDF、一个未知扩展名文件点「打开方式」，列表里能看到「拾用·定位文件位置」，且**没有**把拾用设成默认的提示。
- `file://` 可访问共享存储文件：切到真实父目录并高亮。
- ExternalStorageProvider `primary:` content URI，已具父目录访问能力：定位并高亮。
- FileProvider / 云盘单文件 content URI：显示授权限制，不复制；「文件详情」仍显示原 URI 元数据。
- 点击「授权父目录」：选对目录后定位；选错目录明确拒绝。
- 无 `FLAG_GRANT_READ_URI_PERMISSION` 的 content URI、私有目录 file URI：拒绝。
- 多文件分享：只出现显式导入流程。
- 管理器已在前台时，再从别的应用「打开方式」传第二个文件：窗口复用后仍执行定位（`onNewIntent` 路径）；正在复制时传入会提示排队并在结束后执行。
