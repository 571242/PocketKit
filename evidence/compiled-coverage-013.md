# compiled-coverage-013 说明

`compiled-coverage-013.json` 是在**宿主 JVM** 上对编译产物做反射探测得到的，命令：

```
python3 tools/release-coverage.py \
  --classes .scratch/lead/merged-classes \
  --catalog app/src/main/assets/selected-tools.json \
  --out evidence/compiled-coverage-013.json
```

结果：`catalogCount=110`，`supportedIds` 110 个，`errors=[]`。

## 为什么要合并一个 Build 桩

`release-coverage.py` 把 `android.jar`（stub，方法体全是 `throw new RuntimeException("Stub!")`）
放进 classpath 后反射调用各模块的 `supports(String)`。`BrowserHomeActivity.supports`
（N02 无痕浏览）的实现是：

```java
return "N02".equals(id) && Build.VERSION.SDK_INT >= 28;
```

宿主 JVM 上 `android.jar` 的 `Build.VERSION.SDK_INT` 不是真实运行值，读取时要么抛
`NoClassDefFoundError` 要么得到 0，于是 N02 会被误判为不支持。这个问题从 0.12.0
引入 N02 起就存在，与本次 L01/L02 无关。

为了得到「在目标设备上真实会是什么结果」，本次把下面这个**仅用于宿主校验、不进入 APK**
的桩放在探测 classpath 最前面，取 SDK 36（与 `compileSdk`/`targetSdk` 一致）。桩源码就是下面
这段（临时目录 `.scratch/lead/sdkstub/android/os/Build.java`，跑完即删，不进仓库）：

```java
package android.os;
public final class Build {
    public static final class VERSION { public static final int SDK_INT = 36; }
}
```

命令见上（`.scratch/lead/merged-classes` = 该桩 + `app/build/intermediates/javac/debug/.../classes`）。
`.scratch` 不参与打包，`tools/package-release.py` 会排除它。

## 结论

- L01 / L02 由 `MockLocationActivity.supports` 直接返回 true，与 Build 版本无关。
- N02 在 API ≥ 28 的真实设备上为 true，本次 110/110 是设备上的真实预期。
- 不合并桩时脚本会报 `FAIL compiled coverage: ['N02']`，属于宿主环境限制，不是代码缺陷。
