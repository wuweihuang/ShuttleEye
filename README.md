# ShuttleEye 羽眼 —— 羽毛球鹰眼轨迹捕捉与测速系统（iOS 版）

基于多个开源羽毛球视觉项目深度优化的 iOS 应用：**实时捕捉羽毛球飞行轨迹、测量击球瞬间峰值球速，并对测速结果进行物理自洽性验证**。

## 开源项目基础

本项目整合并优化了以下开源成果：

| 项目 | 核心贡献 | 本项目如何使用/优化 |
|---|---|---|
| [TrackNetV3](https://github.com/qaz812345/TrackNetV3)（318★，ACM MM 2023） | 羽毛球热图追踪网络，准确率 97.51%；轨迹修复 | 提供 CoreML 模型集成路径 + 轨迹修复思想（离群剔除 + 间隙插值） |
| [TrackNetV2](https://nol.cs.nctu.edu.tw:234/open-source/TrackNetv2/)（NYCU） | 高效小目标羽球检测 | 3 帧灰度堆叠输入约定 |
| [Badminton_Challenge](https://github.com/jean50621/Badminton_Challenge)（IJCAI 2023 金奖） | 全深度学习分析系统、轨迹插值重建 | 轨迹重建策略参考 |
| [MonoTrack](https://arxiv.org/abs/2204.01899) | 单目 3D 轨迹重建（球场/物理约束） | 物理约束验证思路 |
| 移动端杀球测速系统（[arXiv:2509.05334](https://arxiv.org/abs/2509.05334)） | 卡尔曼滤波 + 复合评分（0.3/0.7）+ 时空标定 + 启发式速度边界（5~375 km/h），iPhone 16 30fps 实测 | 核心测速管线直接继承并增强 |

## 相对开源原项目的优化（"最大程度优化"清单）

| # | 优化点 | 原项目 | 本项目 |
|---|---|---|---|
| 1 | 跟踪门限 | 固定 W/4，低帧率丢球 | **自适应门限** max(W/4, 2.5·|v̂|·dt)，30fps 也不丢球 |
| 2 | 质心定位 | 包围盒几何中心 | **亮度加权亚像素质心**（误差 <1px） |
| 3 | 运动模糊 | 未处理 | **前缘补偿**：质心沿运动方向前移 blur/2，消除拖影帧系统偏差 |
| 4 | 速度验证 | 无 | **空气阻力物理验证**：拟合 dv/dt=−k·v²，输出 R²、可疑段标记、鲁棒二次拟合 |
| 5 | 雷枪对比 | 无 | **雷达等效速度**：闭式解 v(x)=1/(1/v₀+k·x)，推算 4m 处读数与雷达枪直接可比 |
| 6 | 误差量化 | 无 | **不确定度** σ_v=√2·σ_px·S_f/dt，自动给出 ±km/h |
| 7 | 帧率 | 论文实测 30fps | **240/120fps 采集**（误差 ∝1/帧率，误差直接降低 4~8 倍） |
| 8 | 人工复核 | 论文提出 | **轨迹图点击剔除异常帧**并即时重算 |
| 9 | 卡尔曼收敛 | 零速冷启动 | **双点种入速度**，击球瞬间即收敛 |
| 10 | 检测器 | 需训练模型 | **双引擎**：CoreML TrackNet（可选）+ 经典 CV 帧差（开箱即用） |

## 项目结构

```
ShuttleEye/
├── project.yml                    # XcodeGen 工程定义
├── Package.swift                  # 核心算法库（可独立 swift test）
├── Core/                          # 平台无关核心算法（ShuttleEyeCore）
│   ├── Detection/                 #   检测协议 + 经典 CV 帧差检测器
│   ├── Tracking/                  #   卡尔曼滤波 / 复合评分跟踪 / 轨迹修复
│   ├── Speed/                     #   时空标定 / 测速 / 物理验证
│   └── Pipeline/                  #   端到端分析管线
├── App/                           # iOS App（SwiftUI + AVFoundation）
│   ├── Camera/                    #   240fps 相机服务、预览
│   ├── Media/                     #   像素→灰度转换、视频帧读取
│   ├── Detection/                 #   TrackNet CoreML 检测器
│   ├── Pipeline/                  #   分析调度
│   ├── Views/                     #   实时测速 / 视频分析 / 标定 / 结果 / 历史 / 原理
│   └── Models|Store/              #   记录与存储
├── Tests/                         # 单元测试（算法精度验证）
├── Tools/
│   └── convert_tracknet_to_coreml.py  # TrackNetV3 → CoreML 转换脚本
```

## 构建步骤（需 macOS + Xcode 15+）

```bash
# 1. 安装 XcodeGen（生成 .xcodeproj）
brew install xcodegen

# 2. 生成并打开工程
cd ShuttleEye
xcodegen generate
open ShuttleEye.xcodeproj

# 3. 选择真机（需要相机）→ Cmd+R 运行
```

核心算法库可跨平台独立测试（Swift 5.9）：

```bash
swift test        # 运行 4 组精度测试
```

## 使用流程

1. **标定**：三脚架固定手机（侧面垂直于球的飞行平面），点击「标定比例尺」，在冻结帧上沿一条与击球点等距的场地线段（如双打边线 6.10m）点击两点；
2. **实时测速**：点击捕捉按钮录制约 2 秒（240fps），自动完成 检测→跟踪→修复→测速→验证；
3. **视频分析**：或从相册选择慢动作视频走同一管线；
4. **结果复核**：峰值球速 ± 不确定度、物理验证徽章、雷达等效速度、速度曲线；在轨迹图上点击可剔除异常帧并自动重算；
5. **历史**：所有测量自动存档，可回看曲线与参数。

## 获得最高精度（TrackNet 模型，可选）

默认使用经典 CV 帧差检测（无需模型）。追求 TrackNetV3 级 97.5% 检测准确率：

```bash
python Tools/convert_tracknet_to_coreml.py \
    --weights TrackNetV3/ckpt/TrackNetV3_best.pt --out TrackNetShuttle.mlmodel
xcrun coremlcompiler compile TrackNetShuttle.mlmodel .
# 将 TrackNetShuttle.mlmodelc 拖入 Xcode 工程，App 启动时自动优先加载
```

## 速度验证方法论（为什么峰值 > 雷达枪）

羽毛球气动阻力极大（击球瞬间减速度可达 ~200g），球速沿程快速衰减。本系统测量**击球瞬间的真实峰值**；雷达枪需球飞行数米才锁定，读到的是衰减后速度。arXiv:2509.05334 的 20 次对照实验表明：系统"近网处速度"与雷达枪读数高度吻合，证明全轨迹速度还原准确。App 内的物理一致性验证（R²、气动系数 k、可疑段标记）与雷达等效速度即基于该结论实现。

## 局限与注意事项

- 经典 CV 检测器要求**三脚架固定机位**（机位抖动会污染帧差）；TrackNet 模型模式抗抖动更强；
- 测得速度为**飞行平面内 2D 速度**：侧机位拍摄杀球/平抽时即真实球速；斜向球路会有投影偏差（后续版本可加入 MonoTrack 式 3D 重建）；
- 标定线段必须与击球点等距，否则比例尺误差直接进入结果；
- 室内无风环境验证有效，户外风场会破坏阻力模型假设。
