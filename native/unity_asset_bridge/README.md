# Unity Android 原生解析组件

本目录是随身工具箱的窄 JNI 桥接层。Kotlin 只向它传入可随机读取的本机路径、图片/音频/视频/文本选择和输出上限；Unity 容器、序列化对象及 `.resS`/`.resource` 伴随资源的读取由固定版本的 `unity-rs-core` 完成。

## 当前解析策略

- 输入分析优先检查 `UnityFS`/`UnityWeb`/`UnityRaw` 与 SerializedFile 头，而不是只依赖扩展名；APK、ZIP、SAF 目录中的无后缀或哈希命名资源也会参与识别。
- 识别 `.split0`、`.split1` 等分片以及 `.resS`/`.resource` 伴随流。对可随机读取的 APK/ZIP，Kotlin 通过 ZIP 中央目录只打开 Unity 候选条目；无法随机读取时回退到顺序流式扫描。两种路径都只按原相对路径暂存候选，Unity archive 和 SerializedFile 的读取仍交给 `unity-rs-core`。
- Addressables 作为普通 AssetBundle 来源处理：不要求固定的路径深度、平台目录、group 名称或 bundle 文件名；`.bundle` 及无后缀/哈希命名候选通过通用扩展名与内容签名进入同一解析链路。
- 单个且可寻址的松散 Unity 文件通过保持打开的文件描述符直接随机读取。APK/ZIP、目录或不可寻址来源仅暂存已识别的 Unity 候选，并在复制前检查缓存容量、任务结束后清理。
- 可选导出 Texture2D、Texture2DArray、Sprite、AudioClip、VideoClip、MovieTexture 与 TextAsset。每个 SerializedFile 的产物子路径内按 `picture`、`audio`、`video`、`text` 分类；视频保留原容器，图片以无损快速压缩档编码为 PNG。每次对象导出同时生成 `unity_export_manifest.json`，记录源文件、path ID、class ID、载荷类型、分类后的输出相对路径/大小及失败诊断；清单先写临时文件，完整落盘后再发布。
- 重复导出采用增量跳过：Kotlin 只索引 `0000_...` 等 SerializedFile 产物目录中的分类文件，并以“分组目录 + 分类 + path ID”传给原生层；某个 class 的对象全部存在时，该 class 不再进入纹理解码/音视频读取。部分存在时继续解析以补齐缺失项，但写回 SAF 前会逐路径跳过已有同名文件，不创建 `(2)` 副本。原始 Unity 文件也在读取或解压前执行同样的路径检查。
- 单对象失败、未知类型或不支持的对象不会中断整个任务；输出总量、单对象大小、清单记录数与诊断数均有限制。

Font、Mesh、Shader、Material、AnimationClip、MonoBehaviour 等也是常见 Unity 资源。当前桥接层为未来的 `font`、`model`、`shader` 分类预留了映射，但不会默认解析它们，以免显著增加移动端任务耗时；代码产物与原始容器继续按原相对路径导出，不混入媒体分类目录。

解析耗时主要来自纹理解码/PNG 编码和通过 SAF 逐文件写入用户目录。当前使用 `PngCompression::Fast`，并将大量产物的进度状态改为分批刷新；快速 PNG 仍是无损输出，但通常会以略大的文件换取更短的编码时间。Rust Debug/Release 分别使用 `opt-level = 2/3`，以增加首次编译时间和少量 APK 体积为代价改善设备端解析速度。

当前不会解析完整 Addressables catalog 来还原地址/依赖名称，也不会下载远程资源、重打包 APK、绕过自定义加密或完整还原原 Unity 工程。`catalog.json`/`settings.json` 用于 Addressables 的运行时定位与命名，但不是读取本地 UnityFS bundle 内对象的前置条件。

## Android Studio 构建前准备

1. 在 Android Studio 的 SDK Manager 中安装 NDK r30（`30.0.16248370`，Side by side）。项目已通过 `android.ndkVersion` 固定该版本；只安装其它版本时，AGP 不会自动改用它。
2. 安装 Rust stable 1.88 或更新版本。Rust 1.99 符合要求，不需要降级。
3. 为四个 APK 架构安装标准库：

   ```text
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
   ```

4. 正常从 Android Studio 构建。`net.mullvad.rust-android` 会在合并 JNI 库之前调用 Cargo，不需要另装 `cargo-ndk`。

安装 Rust 后应重启 Android Studio，让新 PATH 对 IDE 生效。若 Android Studio 在 Windows 上仍找不到 Cargo，可在不提交版本库的 `local.properties` 中设置（推荐使用正斜杠，避免 `.properties` 转义问题）：

```text
rust.cargoCommand=C:/Users/你的用户名/.cargo/bin/cargo.exe
rust.rustcCommand=C:/Users/你的用户名/.cargo/bin/rustc.exe
```

首次构建需要联网下载 Cargo 依赖和已固定提交的 `unity-rs-core`。当前固定提交为 `30badcbe5f0acef5ee592c5d765d52c5c61f78e6`（unity-rs 0.6.2）。

## Cargo 下载失败

项目根目录的 `.cargo/config.toml` 将 crates.io 替换为 RsProxy 的 sparse 镜像，并增加网络重试及超时时间。因此包括 `windows-sys` 在内的 crates 不再直接从 `static.crates.io` 下载。该配置只作用于本项目，不会修改用户目录下的全局 Cargo 设置。

重新构建时，日志应出现 `Updating rsproxy-sparse index`。若仍显示 `https://static.crates.io/...`，先停止当前构建并重启 Android Studio，确认项目根目录的 `.cargo/config.toml` 没有被删除；同时检查是否通过 `--config` 或环境变量给 Cargo 注入了更高优先级的注册表设置。不要删除已经成功下载的 `%USERPROFILE%/.cargo/registry` 缓存。

RsProxy 仅代理 crates.io 注册表依赖；固定提交的 `unity-rs-core` 仍从 GitHub 获取。项目已经启用 `git-fetch-with-cli`，会复用系统 Git 的网络配置。
