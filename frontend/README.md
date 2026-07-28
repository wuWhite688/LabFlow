# LabFlow 前端

实验室设备预约、审批与故障工单协同平台的前端。

LabFlow 提供统一的设备排期、预约申请、教师审批、维修工单流转和操作审计能力。学生、教师、维修员和管理员可通过同一工作台完成各自流程。

## 技术栈

- Vinext (基于 Vite + React Server Components)
- TypeScript + React
- 响应式设计，支持 390px 手机端紧凑筛选

## 主要功能模块

- 设备列表与状态筛选（可用/维护中/已退役）
- 设备预约提交与时间冲突检查
- 预约审批流（待审批 → 批准/拒绝）
- 故障工单：提交 → 派单 → 认领 → 处理 → 解决/关闭
- 操作审计日志
- 角色化视图（学生 / 教师 / 维修员 / 管理员）

## 快速开始

```bash
cd frontend
npm install
npm run dev
npm run build
npm test
```

## 目录结构

- `app/` – 页面与视图组件（EquipmentView、ReservationsView、WorkOrdersView、AuditView）
- `app/components/views/` – 各业务视图
- `app/lib/` – 类型、角色、API 封装
- `public/` – 静态资源

## 开发与测试

- `npm run dev`：本地开发服务器
- `npm run build`：生产构建验证
- `npm test`：构建后运行前端测试

## 手机端优化

390px 宽度下筛选区已收紧（更小内边距、输入高度和字体），保证单手操作不拥挤。

## 时间数据

演示数据中的预约起止时间、工单创建/更新时间、审计日志时间均使用自然错开的整点与半点（如 09:00、09:30、10:00、14:30 等），便于排期展示与测试。

## 注意事项

- 前端通过 API 与后端交互，认证信息由平台注入。
- 所有写操作需相应角色权限。
- 构建产物位于 `dist/`。

LabFlow 前端为实验室设备全生命周期管理提供清晰、高效的界面。
