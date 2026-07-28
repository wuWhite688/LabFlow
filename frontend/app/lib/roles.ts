import type { Role, View } from "./types";

export type NavItem = { id: View; icon: string; label: string };

/** Role-scoped navigation — same app shell, different visible entries. */
export function navItemsForRole(role: Role): NavItem[] {
  switch (role) {
    case "STUDENT":
      return [
        { id: "dashboard", icon: "⌂", label: "我的工作台" },
        { id: "equipment", icon: "▦", label: "设备查询" },
        { id: "reservations", icon: "◷", label: "我的预约" },
        { id: "workorders", icon: "◇", label: "我的报修" },
      ];
    case "TEACHER":
      return [
        { id: "dashboard", icon: "⌂", label: "教学工作台" },
        { id: "equipment", icon: "▦", label: "设备管理" },
        { id: "reservations", icon: "◷", label: "预约审批" },
        { id: "workorders", icon: "◇", label: "故障动态" },
      ];
    case "TECHNICIAN":
      return [
        { id: "dashboard", icon: "⌂", label: "维修工作台" },
        { id: "equipment", icon: "▦", label: "设备状态" },
        { id: "workorders", icon: "◇", label: "维修工单" },
      ];
    case "ADMIN":
    default:
      return [
        { id: "dashboard", icon: "⌂", label: "运营总览" },
        { id: "equipment", icon: "▦", label: "设备中心" },
        { id: "reservations", icon: "◷", label: "预约管理" },
        { id: "workorders", icon: "◇", label: "故障工单" },
        { id: "audit", icon: "≣", label: "操作审计" },
      ];
  }
}

export function workspaceTitle(role: Role, view: View): string {
  const item = navItemsForRole(role).find((entry) => entry.id === view);
  if (item) return item.label;
  // Fallback if view is hidden for role
  const defaults: Record<View, string> = {
    dashboard: "工作台",
    equipment: "设备",
    reservations: "预约",
    workorders: "工单",
    audit: "审计",
  };
  return defaults[view];
}

export function workspaceEyebrow(role: Role): string {
  switch (role) {
    case "STUDENT":
      return "STUDENT DESK / 学生实验助手";
    case "TEACHER":
      return "TEACHER DESK / 教师审批台";
    case "TECHNICIAN":
      return "TECH DESK / 维修作业台";
    case "ADMIN":
    default:
      return "LAB CONTROL DESK / 实验室运行台";
  }
}

export type DashboardCopy = {
  kicker: string;
  title: string;
  subtitle: string;
  hint: string;
};

export function dashboardCopy(role: Role, displayName: string): DashboardCopy {
  switch (role) {
    case "STUDENT":
      return {
        kicker: "MY LAB DESK / 我的实验待办",
        title: `${displayName}，从这里预约设备或上报故障`,
        subtitle: "查看我的预约进度、报修状态，并一键发起新的预约或故障单。",
        hint: "学生视图 · 仅展示与你相关的预约和报修",
      };
    case "TEACHER":
      return {
        kicker: "APPROVAL DESK / 审批与排期",
        title: `${displayName}，优先处理待审批预约`,
        subtitle: "关注待审批队列、近期设备排期，并维护实验室设备台账。",
        hint: "教师视图 · 审批、排期与设备管理",
      };
    case "TECHNICIAN":
      return {
        kicker: "REPAIR DESK / 接单与处置",
        title: `${displayName}，待接单池与在办工单已更新`,
        subtitle: "从待接单池认领故障，跟进我的处理中工单，并同步维修状态。",
        hint: "维修员视图 · 接单池 + 我的在办单",
      };
    case "ADMIN":
    default:
      return {
        kicker: "OPS CONTROL / 全局运营",
        title: `${displayName}，实验室运行指标已同步`,
        subtitle: "总览设备与工单态势，从首页进入派单、异常排查与操作审计。",
        hint: "管理员视图 · 全局指标、派单与审计",
      };
  }
}

export function isViewAllowed(role: Role, view: View): boolean {
  return navItemsForRole(role).some((item) => item.id === view);
}
