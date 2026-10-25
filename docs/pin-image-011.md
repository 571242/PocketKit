# 贴屏参考图（I01 / 011 实现记录）

> 状态：代码与宿主自测完成，**未经真机验证**。本文只写已经跑过命令验证的事实，
> 未验证项集中列在 §7。

## 1. 到什么程度

一张参考图钉在屏幕最上层，可以单指拖动、双指等比缩放、调不透明度、收起成屏幕上的小块、
关掉。**只做一张**：再次开启会替换当前那张，不会堆叠窗口。

不读取其他应用的画面，不做录屏，不注入点击，不申请无障碍。它只是"把我看过的图放在最上面"，
不能替代分屏。

## 2. 输入契约

### 2.1 应用内入口

`PinImageActivity`（公开类，`public static boolean supports(String id)` / `String limitation(String id)`，
与 `ManagerActivity`、`VoiceOverlayActivity` 同一种反射契约）：

| 项目 | 值 |
| --- | --- |
| `supports(id)` | 仅 `"I01"` |
| `limitation(id)` | 一句中文限制说明，供首页未就绪提示复用 |
| 选图 | `ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE`，`setType("image/*")`，带读写授权与 persistable 标记 |
| 预览 | 选完立刻把字节复制进应用缓存再解码，预览显示实际解码尺寸 |

### 2.2 接收系统分享

`ACTION_SEND` + `type="image/*"`，读 `Intent.EXTRA_STREAM`。系统截图分享面板直接选中本应用即可。
`getData()` 不为空时同样作为输入，供文件管理器/其它管理器调用（`VIEW` 也走这条）。

授权处理：拿到 URI 立刻 `takePersistableUriPermission`，失败（截图提供方只给一次性授权）不阻断；
真正依赖的是"当场把字节复制到 `cacheDir/pins/`"，之后覆盖层只读自己的文件，永久不需要再访问原 URI。

### 2.3 服务参数

`PinOverlayService.EXTRA_PATH`：`String`，应用缓存目录里的绝对路径（由 Activity 复制好的那份）。
`ACTION_STOP`：关闭贴屏（通知按钮与操控条"关闭"共用同一路径）。

## 3. 交互

| 操作 | 位置 |
| --- | --- |
| 移动 | 单指拖动参考图 |
| 缩放 | 双指捏合，等比，最小 96 dp，最大到屏幕宽/可用高 |
| 不透明度 | 底部操控条第一格，100% → 75% → 50% → 30% 循环 |
| 大小 | 操控条"缩小/放大"（×0.82 / ×1.22），与捏合同一套边界 |
| 收起 | 操控条"收起"，或收起后轻点小块展开 |
| 关闭 | 操控条"关闭"，或前台通知"关闭"，或入口页"关闭贴屏" |

操控条是一条 340 dp 以内的窄条，贴在底部偏上，不占整屏、不抢焦点。参考图窗口是 `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`，不挡输入法。

收起是**真的把窗口缩成 64 dp**，不是把大窗口透明化：触摸区域同步变小，下面的应用才能点到。

## 4. 位图、EXIF、内存

- 抽样：`inJustDecodeBounds` 读原始尺寸 → `inSampleSize` 取 2 的幂，长边落到
  `decodeSide()`（屏幕长边，夹在 720..2048）。2048² ARGB_8888 约 16 MB。
- EXIF：用 `androidx.exifinterface` 读 `TAG_ORIENTATION`，把 1..8 全部映射成
  旋转 + 镜像矩阵后 `createBitmap` 重绘；重绘成功后立刻 `recycle()` 中间位图。
  非 JPEG 读不到该标签时按 normal 处理，不阻断。
- 释放：服务销毁、换图替换旧图、解码完成但服务已销毁、解码抛异常，四条路径都 `recycle()`。
  缓存文件在解码成功/失败后立刻 `delete()`。
- 旋转：`onConfigurationChanged` 里按新屏幕重新 `scaled()` + `onScreen()` 夹取，
  收起的块与操控条宽度也重算。画面不会留在屏幕外。

## 5. Manifest 声明（**回交 lead，我未改动共享 Manifest**）

```xml
<!-- 需要已有权限：android.permission.SYSTEM_ALERT_WINDOW、FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE -->
<!-- 已在 Manifest 中，无需新增。POST_NOTIFICATIONS 已在。 -->

<activity android:name=".PinImageActivity" android:exported="true" android:windowSoftInputMode="adjustResize">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="content" android:mimeType="image/*" />
        <data android:scheme="file" android:mimeType="image/*" />
    </intent-filter>
</activity>

<service android:name=".pinoverlay.PinOverlayService" android:exported="false"
         android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="User-started pinned reference image overlay: the user picks one image, it stays on top for visual reference, and is closed explicitly from its control bar or notification." />
</service>
```

要点：

- `android:exported="true"` 只给 Activity。Action 是分享/查看图片，本来就是给别的应用调的；
  服务保持 `exported="false"`。
- 前台类型用 `specialUse`，和本项目 `TicketOverlayService`、`AutomationOverlayService` 一致。
  Android 14+（API 34+）要求 `specialUse` 必须带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明，
  上面这句就是给审核/用户看的一句话。
- **权限不需要新增**：`SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、
  `POST_NOTIFICATIONS` 目前都已在 Manifest 里。如果 lead 之后清理掉其中某个，就需要补回来。
- 目标 26..36 全部走同一路径：API 26+ 用 `TYPE_APPLICATION_OVERLAY`（不用已废弃的 `TYPE_PHONE`），
  API 34+ 用带类型的 `startForeground`，API 31+ 加 `FOREGROUND_SERVICE_IMMEDIATE`，API 33+ 才提示通知权限。
- 目录条目（`selected-tools.json`）需要 lead 侧加 `{"id":"I01","name":"贴屏参考图","category":"图片处理","module":"PinImageActivity"}`。
  我没有改共享资产文件。

## 6. 自测

`app/src/test/java/cn/xianhua/pocketkit/pinoverlay/PinGeometryTest.java`：纯 JDK，无 Android 依赖，
真 assert（失败抛 `AssertionError`）。`PinGeometry` 里全部是要落到像素上的算法：

| 组 | 覆盖 |
| --- | --- |
| sampling | 2 的幂、长边优先、恰好等于上界不缩小、全景图 |
| fitting | 竖图/横图/小图不放大/极端 1px、比例误差 |
| scaling | 放大、触底 96、触顶屏幕宽、超高图被高度上界限制、上界低于下界不出负数、连续 40 步不越界 |
| placement | 居中、比屏幕大时返回 0 不返回负偏移 |
| visibility | 四方向拖出屏幕后的夹取、超大窗口保留可触区、夹取幂等 |
| collapsing | 收起块落在图中心、越界后夹取并保持幂等 |
| alpha | 100→75→50→30→100 循环、未知值回落、百分比夹取 |
| exif | 1/3/5/6/7/8 映射、未知值不动、镜像集合 |
| decodeSide | 手机/平板/小屏/未知屏 |
| rejections | 10 个非法入参都抛 `IllegalArgumentException` |
运行（已实测）：

```bash
F:/mlbuild/tools/jdk-17.0.20.1+1/bin/javac.exe -encoding UTF-8 \
  -d .scratch/pin-image/host-classes \
  app/src/main/java/cn/xianhua/pocketkit/pinoverlay/PinGeometry.java \
  app/src/test/java/cn/xianhua/pocketkit/pinoverlay/PinGeometryTest.java
F:/mlbuild/tools/jdk-17.0.20.1+1/bin/java.exe -ea -cp .scratch/pin-image/host-classes \
  cn.xianhua.pocketkit.pinoverlay.PinGeometryTest
# => PinGeometryTest OK: 194 checks
```

另跑了一次 **只编译、不打包、不跑全局 Gradle** 的 API 36 编译检查
（`.scratch/pin-image/check.sh`，`javac -source 17 -target 17 -cp android-36/android.jar;exifinterface.jar`）：
四个新文件 + 它依赖的 `ToolUi`、`BaseToolActivity` 全部通过，只剩 `BaseToolActivity` 原有
弃用 API 警告与 `getParcelableExtra` / `getRealMetrics` 两处弃用提示（均为可用 API）。

## 7. 真实限制（未经真机，不宣称验证）

1. **没上过真机/模拟器。** 拖动、捏合、透明度、收起块、旋转重排、通知按钮全部只有编译与算法自测，
   触摸手势与 WindowManager 行为未在设备上跑过。
2. 捏合与拖动共存在同一 `OnTouchListener` 里，双指时主动跳过拖动。理论上会跟系统手势导航、
   三指截屏冲突，实际手感未知。
3. `TYPE_APPLICATION_OVERLAY` 在部分厂商 ROM（尤其开了"全面屏手势"或游戏模式）会被降级或裁剪，
   需要真机确认是否仍能盖住游戏/微信等全屏应用。
4. `ACTION_SEND` 的 `EXTRA_STREAM` 只处理单图；多图分享（`ACTION_SEND_MULTIPLE`）没有接，会落到
   别的应用。这是有意的：需求只要求至少一张可靠。
5. 只支持静态图片。GIF/超长截图能解码但会按长边抽样，细节可能糊。
6. HEIF/HEIC 依赖设备解码器，老机型可能直接失败（会 toast"图片未能解码"）。
7. 服务是 `START_NOT_STICKY`，被系统杀掉后不会自动恢复；参考图会消失，需重新开启。
8. 解码上限 2048 是经验值：极大截图（如 1080×12000 长图）抽样后细节不足，属于已知取舍。
9. 缓存目录 `cacheDir/pins/`：正常路径下文件在解码成功或失败后立刻删除，运行期只持有 Bitmap。
   残留只出现在"选了图但没点开启贴屏就杀进程"和"服务在解码途中被销毁"两种情况，
   会被系统缓存清理回收；没有主动扫除逻辑（够用，不值得为它加代码）。
10. 未做多语言、未做深色主题适配：操控条是固定的雾白底 + 深蓝字（与 `ToolUi.fill` 一致），
    在深色系统下依然是浅色条，这是有意的可读性选择而非遗漏。
11. Activity 旋转时用 `onSaveInstanceState` 保住了已缓存文件的路径并重新解码预览，不用再次访问原 URI。
    服务本身不保存任何持久状态：进程被杀后服务是 `START_NOT_STICKY`，参考图不会自动回来。
12. Android 12+ 从后台启动前台服务会被系统拒绝。`PinImageActivity` 捕获该异常并提示"保持本页在前台再点一次"，
    不会留下一个永远起不来的空服务；但真机上的该分支只做了代码处理，未实测。
13. 再次开启会替换当前那张参考图（先 `clearPin()` 再解码新图），不会出现两张叠在一起。
    替换过程不保留上一张的透明度/大小，回到默认 100% 与适配尺寸。
14. 从分享面板直接打开时 intent 里没有 `tool_id`，`BaseToolActivity` 会因此隐藏"返回"按钮；
    `PinImageActivity` 在建视图前补上 `tool_id=I01`，让分享入口也有返回键，未改共享壳。

## 8. 文件

```
app/src/main/java/cn/xianhua/pocketkit/PinImageActivity.java           入口/选图/分享接收/授权引导
app/src/main/java/cn/xianhua/pocketkit/pinoverlay/PinGeometry.java     纯数学：抽样、适配、缩放、夹取、EXIF 映射
app/src/main/java/cn/xianhua/pocketkit/pinoverlay/PinImageLoader.java  URI 复制、抽样解码、EXIF 扶正
app/src/main/java/cn/xianhua/pocketkit/pinoverlay/PinOverlayService.java 前台服务、窗口、手势、操控条
app/src/test/java/cn/xianhua/pocketkit/pinoverlay/PinGeometryTest.java   194 项断言
.scratch/pin-image/check.sh                                            宿主自测 + 只编译检查
```

未改动：`AndroidManifest.xml`、`app/build.gradle`、`selected-tools.json`、任何现有 overlay 服务。
Manifest 片段见 §5，需要 lead 集成时手工加。
