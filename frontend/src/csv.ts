/** 轉成 CSV 欄位：必要時加引號；以 = + - @ 等開頭的文字前加 ' 防止試算表公式注入。 */
export function csvCell(v: string | number): string {
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : ''
  let s = v
  if (/^[=+\-@\t\r]/.test(s)) s = "'" + s
  return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

export function toCsv(rows: (string | number)[][]): string {
  return rows.map(r => r.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

/** 下載 CSV（含 UTF-8 BOM，Excel 開啟中文不亂碼）。 */
export function downloadCsv(filename: string, rows: (string | number)[][]) {
  const blob = new Blob(['﻿' + toCsv(rows)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob), a = document.createElement('a')
  a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
