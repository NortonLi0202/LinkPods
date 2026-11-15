# 原生桥接

当前桥接模板和离线测试。prepare-runtime-bundle.py 默认使用 third_party/sources/librepods/agent.js 和 third_party/payloads 的固定 Frida 输入；生成到明确的新目录，不覆盖已有输出。目标地址由调用方提供。构建及测试不会注入设备。
