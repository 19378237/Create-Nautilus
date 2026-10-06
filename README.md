# Create: Nautilus

Create: Nautilus 是面向 Minecraft 1.21.1 / NeoForge 的 Create Aeronautics 附属模组，为船舶和潜艇加入浮力、水阻、舱室进水、供氧和机械压载系统。

模组将水下航行与 Create 的机械动力结合：船体的浸水情况、舱室空气、进水和压载会影响载具的潜浮与运动；氧气生产、储存和输送设备则为密闭舱室提供供氧。独立的气体管网还支持氢气利用，为螺旋桨和锅炉提供增益。

当前版本：**0.1**。

## 模组内容

- **浮力与水阻**：为物理载具计算水中的浮力和运动阻力，并根据浸水程度调整推进表现。
- **舱室与进水**：检测载具内部舱室及船体开口，处理进水、舱内水量和压力变化，并呈现舱内水体。
- **氧气系统**：密闭舱室储存氧气，玩家在舱内消耗氧气；电解制氧机、机械通气管和氧气接口用于生产与补充氧气。
- **压载控制**：通过机械动力控制前、后舱的水量，改变载具的配重与潜浮状态。
- **气体设备**：提供通用气体储罐、气体管道、机械气体泵、压缩机和减压阀，支持氧气、氢气及其压缩形式的储存与输送。
- **氢气增压**：为 Aeronautics 螺旋桨提供推力增益，为 Create 锅炉增加热量等级，并提供紧急排空设备与氢气储存风险机制。
- **显示与渲染**：支持工程师护目镜容量信息、中英文语言，以及 Iris/Sodium 相关水下渲染兼容。

## 运行环境与依赖

- Minecraft **1.21.1**，Java **21**。
- NeoForge **21.1.228** 或更高的 1.21.1 版本。
- Create **6.0.10**，低于 6.1.0。
- Sable **2.x**；项目构建使用 2.0.3。
- Create Aeronautics **1.3.x**，提供 Aeronautics 与 Simulated 模块。

准确依赖范围见 `src/main/resources/META-INF/neoforge.mods.toml`。Iris、Sodium 和 JEI 为可选组件。

## 从源码构建

安装 JDK 21，在项目根目录执行：

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

Linux/macOS 使用 `bash ./gradlew build --no-daemon --console=plain`。

首次构建需要联网下载依赖。产物位于 `build/libs/`，其中 `create_nautilus-0.1.jar` 为模组，`*-sources.jar` 为源码包。

## 项目结构

- `src/main/java/`：模组源码。
- `src/main/resources/`：模型、贴图、语言文件、标签与模组元数据。
- `build.gradle`、`settings.gradle`、`gradle.properties`：构建、依赖与版本配置。
- `gradlew`、`gradlew.bat`、`gradle/wrapper/`：Gradle Wrapper。
- `gradle/verify-*.gradle`：构建验证任务。

## 许可证

本项目采用 [MIT License](LICENSE)。第三方依赖与资源说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。
