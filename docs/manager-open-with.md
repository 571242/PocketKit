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

无需修改。当前 `ManagerActivity` 已导出并声明：

- `ACTION_VIEW` + `content` / `file` + `*/*`
- `ACTION_SEND` / `ACTION_SEND_MULTIPLE` + `*/*`

若 lead 后续收紧过滤器，必须保留上述 VIEW/SEND 覆盖和 `CATEGORY_DEFAULT`；不需要为未知扩展名逐一增加 MIME。

## 最小验证

已执行：

1. 独立 JVM `ManagerAlgorithmsTest.main`：**299 assertions PASS**，新增覆盖 `primary:` documentId 正常路径、非 primary 卷回退、绝对路径/反斜杠/空段/`.`/`..` 拒绝。
2. `ManagerOpenWithContractTest.main`：覆盖 Manifest 入口、单文件定位、高亮、授权/详情回退及不按名称猜测身份；集成复核另补重复 URI 去重与祖先目录链断言（共 7 项）。
3. 使用 JDK 17 + SDK 36 `android.jar` 定向编译 `ToolUi`、`BaseToolActivity`、`ManagerAlgorithms`、`ManagerStore`、`ManagerShareProvider`、`ManagerActivity`：**成功，无编译错误**；仅有既有弃用 API 提示。

未运行整包 Gradle、APK、模拟器或真机，按协作约定留给 lead。真机至少验证：

- `file://` 可访问共享存储文件：切到真实父目录并高亮。
- ExternalStorageProvider `primary:` content URI，已具父目录访问能力：定位并高亮。
- FileProvider / 云盘单文件 content URI：显示授权限制，不复制；“文件详情”仍显示原 URI 元数据。
- 点击“授权父目录”：选对目录后定位；选错目录明确拒绝。
- 无 `FLAG_GRANT_READ_URI_PERMISSION` 的 content URI、私有目录 file URI：拒绝。
- 多文件分享：只出现显式导入流程。
