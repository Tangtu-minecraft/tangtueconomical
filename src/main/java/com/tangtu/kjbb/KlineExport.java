package com.tangtu.kjbb;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.List;

/**
 * K 线导出：生成一个自包含的 HTML 文件，浏览器直接打开就能看蜡烛图。
 * 不依赖任何外部库。
 */
public final class KlineExport {

    private KlineExport() {
    }

    public static String write(String itemId, List<KlineStore.Candle> cs) {
        try {
            var dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("kjbb").resolve("kline-export");
            Files.createDirectories(dir);
            String safe = itemId.replace(':', '_').replace('/', '_');
            File out = dir.resolve(safe + ".html").toFile();

            StringBuilder js = new StringBuilder("[");
            for (int i = 0; i < cs.size(); i++) {
                var c = cs.get(i);
                if (i > 0) {
                    js.append(',');
                }
                js.append('[').append(c.start()).append(',')
                        .append(c.open()).append(',')
                        .append(c.high()).append(',')
                        .append(c.low()).append(',')
                        .append(c.close()).append(',')
                        .append(c.volume()).append(']');
            }
            js.append(']');

            String period = String.valueOf(KlineStore.periodMs() / 60_000L);
            try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
                pw.println("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">");
                pw.println("<title>K 线 · " + itemId + "</title>");
                pw.println("<style>");
                pw.println("body{background:#1b1b1f;color:#ddd;font-family:Consolas,monospace;margin:0;padding:16px}");
                pw.println("h1{font-size:16px;color:#e8c471;margin:0 0 8px}");
                pw.println(".sub{color:#888;font-size:12px;margin-bottom:12px}");
                pw.println("canvas{background:#111;border:1px solid #333;display:block}");
                pw.println("table{border-collapse:collapse;margin-top:14px;font-size:12px}");
                pw.println("th,td{border:1px solid #333;padding:3px 8px;text-align:right}");
                pw.println("th{background:#222;color:#aaa}");
                pw.println(".up{color:#4caf50}.down{color:#ef5350}");
                pw.println("</style></head><body>");
                pw.println("<h1>" + itemId + "</h1>");
                pw.println("<div class=\"sub\">每根 " + period + " 分钟 · 共 "
                        + cs.size() + " 根 · 导出时间 " + new java.util.Date() + "</div>");
                pw.println("<canvas id=\"c\" width=\"1100\" height=\"420\"></canvas>");
                pw.println("<script>");
                pw.println("const D=" + js + ";");
                pw.println("const cv=document.getElementById('c'),g=cv.getContext('2d');");
                pw.println("const W=cv.width,H=cv.height,PL=60,PR=16,PT=16,PB=30;");
                pw.println("let hi=0,lo=1e18;for(const d of D){hi=Math.max(hi,d[2]);lo=Math.min(lo,d[3]);}");
                pw.println("if(hi===lo){hi=lo+1}const pad=(hi-lo)*0.08;hi+=pad;lo-=pad;");
                pw.println("const n=D.length,bw=(W-PL-PR)/n;");
                pw.println("const y=v=>PT+(hi-v)/(hi-lo)*(H-PT-PB);");
                pw.println("g.strokeStyle='#333';g.lineWidth=1;");
                pw.println("for(let i=0;i<=4;i++){const v=lo+(hi-lo)*i/4;const yy=y(v);");
                pw.println("g.beginPath();g.moveTo(PL,yy);g.lineTo(W-PR,yy);g.stroke();");
                pw.println("g.fillStyle='#666';g.font='11px Consolas';g.fillText(v.toFixed(1),6,yy+4);}");
                pw.println("for(let i=0;i<n;i++){const d=D[i];const x=PL+bw*i+bw/2;");
                pw.println("const up=d[4]>=d[1];g.strokeStyle=up?'#4caf50':'#ef5350';g.fillStyle=up?'#4caf50':'#ef5350';");
                pw.println("g.beginPath();g.moveTo(x,y(d[2]));g.lineTo(x,y(d[3]));g.stroke();");
                pw.println("const top=y(Math.max(d[1],d[4])),bot=y(Math.min(d[1],d[4]));");
                pw.println("g.fillRect(x-Math.max(1,bw*0.3),top,Math.max(2,bw*0.6),Math.max(1,bot-top));}");
                pw.println("g.fillStyle='#666';g.fillText('最高 '+hi.toFixed(1),W-PR-70,PT-4);");
                pw.println("</script>");
                pw.println("<table><tr><th>时间</th><th>开</th><th>高</th><th>低</th><th>收</th><th>量</th></tr>");
                var fmt = new java.text.SimpleDateFormat("MM-dd HH:mm");
                for (int i = cs.size() - 1; i >= 0 && i >= cs.size() - 40; i--) {
                    var c = cs.get(i);
                    String cls = c.close() >= c.open() ? "up" : "down";
                    pw.println("<tr><td>" + fmt.format(new java.util.Date(c.start())) + "</td><td>"
                            + c.open() + "</td><td>" + c.high() + "</td><td>" + c.low()
                            + "</td><td class=\"" + cls + "\">" + c.close() + "</td><td>"
                            + c.volume() + "</td></tr>");
                }
                pw.println("</table></body></html>");
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return "导出失败：" + t;
        }
    }
}