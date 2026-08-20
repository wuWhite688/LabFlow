import type { Role } from "./types";

export const roleLabels: Record<Role, string> = {
  STUDENT: "学生", TEACHER: "教师", TECHNICIAN: "维修员", ADMIN: "管理员",
};

export const statusLabels: Record<string, string> = {
  AVAILABLE: "可预约", IN_USE: "使用中", MAINTENANCE: "维护中", RETIRED: "已退役",
  PENDING: "待审批", APPROVED: "已批准", REJECTED: "已拒绝", CANCELLED: "已取消", EXPIRED: "已过期", COMPLETED: "已完成",
  SUBMITTED: "待派单", ASSIGNED: "已派单", IN_PROGRESS: "处理中", RESOLVED: "已解决", CLOSED: "已关闭",
  LOW: "低", MEDIUM: "中", HIGH: "高", URGENT: "紧急",
};

export function isReservableStatus(status: string) {
  return status !== "RETIRED" && status !== "MAINTENANCE";
}

export const actionLabels: Record<string, string> = {
  EQUIPMENT_CREATED: "创建设备", EQUIPMENT_UPDATED: "更新设备", EQUIPMENT_RETIRED: "退役设备",
  EQUIPMENT_RESTORED: "恢复设备", RESERVATION_CREATED: "提交预约", RESERVATION_APPROVED: "批准预约",
  RESERVATION_REJECTED: "拒绝预约", RESERVATION_CANCELLED: "取消预约", RESERVATION_COMPLETED: "完成预约",
  RESERVATION_EXPIRED: "预约过期", WORK_ORDER_CREATED: "提交工单", WORK_ORDER_ASSIGNED: "派单",
  WORK_ORDER_CLAIMED: "维修员接单", WORK_ORDER_IN_PROGRESS: "开始维修", WORK_ORDER_RESOLVED: "解决故障",
  WORK_ORDER_CLOSED: "关闭工单", WORK_ORDER_CANCELLED: "取消工单",
};

export const formatDate = (value?: string | null) => value
  ? new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value))
  : "—";

