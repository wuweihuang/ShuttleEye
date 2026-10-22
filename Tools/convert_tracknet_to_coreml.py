#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TrackNetV3 (PyTorch) → CoreML 转换脚本
=====================================

将开源项目 https://github.com/qaz812345/TrackNetV3 的预训练权重转换为
ShuttleEye App 可用的 CoreML 模型（TrackNetShuttle.mlmodel）。

使用步骤：
    # 1. 安装依赖（建议 Python 3.10 + torch 2.x + coremltools 7.x）
    pip install torch coremltools numpy

    # 2. 克隆 TrackNetV3 并下载官方预训练权重
    git clone https://github.com/qaz812345/TrackNetV3.git
    # 权重下载后得到例如 ckpt/TrackNetV3_best.pt

    # 3. 转换
    python convert_tracknet_to_coreml.py \
        --weights TrackNetV3/ckpt/TrackNetV3_best.pt \
        --height 288 --width 512 \
        --out TrackNetShuttle.mlmodel

    # 4. 编译并放入 App
    xcrun coremlcompiler compile TrackNetShuttle.mlmodel .
    # 将 TrackNetShuttle.mlmodelc 拖入 Xcode 工程（App target）

说明：
- TrackNetV3 输入为 3 帧灰度堆叠 (3, H, W)，输出热图 (1, H, W)。
- 本脚本将网络包装为纯推理模块（剥离训练逻辑），并按
  CoreMLShuttleDetector.swift 的约定命名 input / output。
- 若官方仓库的模块结构有更新，仅需调整 import_model() 中的加载方式。
"""

import argparse
import numpy as np
import torch
import torch.nn as nn


# ---------------------------------------------------------------- 模型包装
class TrackNetInferenceWrapper(nn.Module):
    """包装 TrackNetV3：输入 [1,3,H,W] float 0~1，输出热图 [1,1,H,W] sigmoid"""

    def __init__(self, net: nn.Module):
        super().__init__()
        self.net = net

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # 官方 TrackNetV3 前向通常已输出 sigmoid 热图；若未过 sigmoid，可在此补齐
        out = self.net(x)
        if out.min() < 0 or out.max() > 1:
            out = torch.sigmoid(out)
        # 统一为 [1,1,H,W]
        if out.dim() == 4 and out.shape[1] != 1:
            out = out[:, :1]
        return out


def import_model(weights_path: str, height: int, width: int) -> nn.Module:
    """加载 TrackNetV3 权重（按官方仓库结构）"""
    import sys
    sys.path.insert(0, "TrackNetV3")  # 官方仓库目录

    # 官方仓库模型定义位置可能为 model/TrackNetV3.py 等，按实际情况调整：
    from model.TrackNetV3 import TrackNetV3  # noqa: E402

    net = TrackNetV3(in_channels=3, out_channels=1)
    state = torch.load(weights_path, map_location="cpu")
    if isinstance(state, dict) and "model_state_dict" in state:
        state = state["model_state_dict"]
    net.load_state_dict({k.replace("module.", ""): v for k, v in state.items()})
    net.eval()
    return net


# ---------------------------------------------------------------- 转换
def convert(weights: str, height: int, width: int, out: str) -> None:
    import coremltools as ct

    net = TrackNetInferenceWrapper(import_model(weights, height, width))
    example = torch.rand(1, 3, height, width)
    traced = torch.jit.trace(net, example)

    mlmodel = ct.convert(
        traced,
        inputs=[ct.TensorType(name="input",
                              shape=(1, 3, height, width),
                              dtype=np.float32)],
        outputs=[ct.TensorType(name="output")],
        minimum_deployment_target=ct.target.iOS16,
        compute_units=ct.ComputeUnit.ALL,
    )
    mlmodel.author = "ShuttleEye (converted from TrackNetV3)"
    mlmodel.short_description = (
        "TrackNetV3 羽毛球热图检测：输入 3 帧灰度堆叠 [1,3,H,W] (0~1)，"
        "输出热图 [1,1,H,W] (0~1)。"
    )
    mlmodel.save(out)
    print(f"已保存: {out}")
    print("下一步: xcrun coremlcompiler compile "
          f"{out.rsplit('.', 1)[0]}.mlmodel .")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="TrackNetV3 → CoreML")
    ap.add_argument("--weights", required=True, help="TrackNetV3 PyTorch 权重路径")
    ap.add_argument("--height", type=int, default=288, help="输入高度（默认 288）")
    ap.add_argument("--width", type=int, default=512, help="输入宽度（默认 512）")
    ap.add_argument("--out", default="TrackNetShuttle.mlmodel")
    args = ap.parse_args()
    convert(args.weights, args.height, args.width, args.out)
