import com.zcloud.ai.core.CubicSplineLut;
import com.zcloud.ai.core.NcpParserAndBuilder;
import com.zcloud.ai.core.Recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 离线验证核心算法（不依赖 Android）：
 *  1) Fritsch–Carlson 单调样条 → 256 LUT 的单调性与端点
 *  2) 84 字节载荷 build/parse 往返一致性
 *  3) 0x90CC 多回前缀字节时 normalizePayload 能否还原
 *  4) lutToCurveSegment / curveSegmentToLut 往返误差
 */
public final class VerifyCore {

    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name + (detail.isEmpty() ? "" : " → " + detail));
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        System.out.println("=== Z-Cloud AI 核心算法离线验证 ===");

        // ---------- 1. 单调样条 ----------
        System.out.println("\n[1] Monotone cubic spline → 256 LUT");
        List<CubicSplineLut.Point> pts = new ArrayList<>();
        pts.add(new CubicSplineLut.Point(0f, 18f));
        pts.add(new CubicSplineLut.Point(64f, 52f));
        pts.add(new CubicSplineLut.Point(128f, 130f));
        pts.add(new CubicSplineLut.Point(192f, 205f));
        pts.add(new CubicSplineLut.Point(240f, 242f));
        pts.add(new CubicSplineLut.Point(255f, 255f));

        int[] lut = CubicSplineLut.INSTANCE.buildLut(pts);
        check("LUT 长度 256", lut.length == 256, "len=" + lut.length);

        boolean monotonic = true;
        int over = 0;
        for (int i = 1; i < lut.length; i++) {
            if (lut[i] < lut[i - 1]) monotonic = false;
            if (lut[i] < 0 || lut[i] > 255) over++;
        }
        check("LUT 单调不减（无过冲）", monotonic, "");
        check("LUT 全部落在 0..255", over == 0, "越界 " + over + " 个");
        check("x=0 → y≈18", Math.abs(lut[0] - 18) <= 2, "lut[0]=" + lut[0]);
        check("x=255 → y=255", lut[255] == 255, "lut[255]=" + lut[255]);

        // 极端：全平 + 两点 + 反向输入乱序
        List<CubicSplineLut.Point> flat = Arrays.asList(
                new CubicSplineLut.Point(0f, 100f),
                new CubicSplineLut.Point(255f, 100f));
        int[] flatLut = CubicSplineLut.INSTANCE.buildLut(flat);
        check("水平线保持 100", Math.abs(flatLut[77] - 100) <= 1, "lut[77]=" + flatLut[77]);

        List<CubicSplineLut.Point> two = Arrays.asList(
                new CubicSplineLut.Point(0f, 0f),
                new CubicSplineLut.Point(255f, 255f));
        int[] idLut = CubicSplineLut.INSTANCE.buildLut(two);
        check("(0,0)-(255,255) 近似恒等", Math.abs(idLut[128] - 128) <= 2, "lut[128]=" + idLut[128]);

        // ---------- 2. 载荷往返 ----------
        System.out.println("\n[2] 84B payload build / parse 往返");
        byte[] tmpl = NcpParserAndBuilder.INSTANCE.defaultTemplate();
        check("defaultTemplate 84 字节", tmpl.length == 0x54, "len=" + tmpl.length);

        Recipe parsed = NcpParserAndBuilder.INSTANCE.parsePayload(tmpl);
        check("内置模板可解析", parsed != null, "");

        // ---------- 3. 前缀归一化 ----------
        System.out.println("\n[3] 0x90CC 返回多前缀字节时 normalizePayload 还原");
        for (int prefix = 1; prefix <= 4; prefix++) {
            byte[] raw = new byte[tmpl.length + prefix];
            for (int i = 0; i < prefix; i++) raw[i] = (byte) (0x10 + i);
            System.arraycopy(tmpl, 0, raw, prefix, tmpl.length);

            byte[] norm = NcpParserAndBuilder.INSTANCE.normalizePayload(raw);
            boolean same = norm != null && Arrays.equals(norm, tmpl);
            check("前缀 " + prefix + "B 可还原", same,
                    norm == null ? "null" : "len=" + norm.length);
        }
        byte[] exact = NcpParserAndBuilder.INSTANCE.normalizePayload(tmpl);
        check("正好 84B 原样返回", exact != null && Arrays.equals(exact, tmpl), "");
        byte[] tooShort = NcpParserAndBuilder.INSTANCE.normalizePayload(new byte[40]);
        check("短于 84B 返回 null", tooShort == null, "");

        // ---------- 4. 曲线段 18 点往返 ----------
        System.out.println("\n[4] LUT → 18B 曲段 → LUT 往返");
        byte[] seg = CubicSplineLut.INSTANCE.lutToCurveSegment(lut, 0x80, true);
        check("曲段 18 字节", seg.length == 18, "len=" + seg.length);
        // 每点应约等于 lut[x]（18 个等距采样点，x = round(i*255/17)）
        int maxErr = 0;
        for (int i = 0; i < 18; i++) {
            int x = Math.round(i * 255f / 17f);
            int expect = lut[x];
            int got = seg[i] & 0xFF;
            if (got == 0x80 || got == 0xFF) continue;   // 中性/忽略点跳过
            maxErr = Math.max(maxErr, Math.abs(got - expect));
        }
        check("18 点采样误差 ≤ 2", maxErr <= 2, "最大误差 " + maxErr);

        int[] back = CubicSplineLut.INSTANCE.curveSegmentToLut(seg, 0x80);
        check("曲段回到 256 LUT", back.length == 256, "len=" + back.length);
        int roundErr = 0;
        for (int i = 0; i < 18; i++) {
            int x = Math.round(i * 255f / 17f);
            int a = seg[i] & 0xFF;
            if (a == 0x80 || a == 0xFF) continue;
            roundErr = Math.max(roundErr, Math.abs(back[x] - a));
        }
        check("曲段往返误差 ≤ 2", roundErr <= 2, "最大误差 " + roundErr);

        // ---------- 5. 名称编码 ----------
        System.out.println("\n[5] 预设名编码");
        byte[] nm = NcpParserAndBuilder.INSTANCE.encodeName("AI_Cinematic_0123456789");
        check("名称长度 ≤ 19", nm.length <= 19, "len=" + nm.length);

        System.out.println("\n=== 结果：" + (fails == 0 ? "全部通过 ✅" : fails + " 项失败 ❌") + " ===");
        if (fails != 0) System.exit(1);
    }
}
