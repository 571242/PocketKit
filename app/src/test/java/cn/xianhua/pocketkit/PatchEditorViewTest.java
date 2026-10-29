package cn.xianhua.pocketkit;

/**
 * JVM self-check for the pixel maths used by the picture text-repair canvas.
 *
 * <p>The class is compiled by the Android build, but this main() is meant to be compiled
 * separately against a small stub of PatchEditorView so it can run without a device. It checks
 * the two real use cases: a clean background patch must erase a dark glyph, and a material that
 * should leave black text on a light ground must not paint the ground over everything.
 */
public final class PatchEditorViewTest {
    public static void main(String[] args) {
        int white = 0xFFFFFFFF;
        int black = 0xFF000000;
        int grey = 0xFF808080;

        require(PatchEditorView.blendPixel(black, white, PatchEditorView.BLEND_NORMAL) == white,
                "正常覆盖：白底切片应覆盖深色字");
        require(PatchEditorView.blendPixel(grey, grey, PatchEditorView.BLEND_NORMAL) == grey,
                "正常覆盖：同色保持不变");

        int multiplied = PatchEditorView.blendPixel(white, black, PatchEditorView.BLEND_MULTIPLY);
        require((multiplied >>> 24) == 0xFF && (multiplied & 0xFFFFFF) == 0,
                "正片叠底：白底黑字应留下黑字");
        int whiteOnWhite = PatchEditorView.blendPixel(white, white, PatchEditorView.BLEND_MULTIPLY);
        require((whiteOnWhite & 0xFFFFFF) == 0xFFFFFF, "正片叠底：白底白片仍为白");

        int screened = PatchEditorView.blendPixel(black, white, PatchEditorView.BLEND_SCREEN);
        require((screened & 0xFFFFFF) == 0xFFFFFF, "滤色：黑底白字应留下白字");
        int blackOnBlack = PatchEditorView.blendPixel(black, black, PatchEditorView.BLEND_SCREEN);
        require((blackOnBlack & 0xFFFFFF) == 0, "滤色：黑底黑片仍为黑");

        int transparent = 0x00FFFFFF;
        require(PatchEditorView.blendPixel(grey, transparent, PatchEditorView.BLEND_NORMAL) == grey,
                "全透明切片不应改动底图");

        System.out.println("PatchEditorViewTest PASS: blend modes and transparent-patch behaviour");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
