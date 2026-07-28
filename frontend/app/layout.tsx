import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "LabFlow · 实验室设备运营平台",
  description: "实验室设备预约、审批与故障工单协同平台",
  icons: { icon: "/favicon.svg", shortcut: "/favicon.svg" },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
