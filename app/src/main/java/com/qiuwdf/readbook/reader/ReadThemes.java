package com.qiuwdf.readbook.reader;

/**
 * 阅读背景主题：每个主题都有白天 / 黑夜两套配色。
 */
public final class ReadThemes {

    /** 白天背景 */
    public static final int[] DAY_BG = {
            0xFFFFFFFF, 0xFFF6EFD9, 0xFFCBE6CF, 0xFFE5E9F2, 0xFFECECEC
    };
    /** 白天正文 */
    public static final int[] DAY_TEXT = {
            0xFF2C2C2C, 0xFF3A3426, 0xFF2F3D31, 0xFF2C3038, 0xFF333333
    };
    /** 黑夜背景 */
    public static final int[] NIGHT_BG = {
            0xFF1A1A1A, 0xFF201B15, 0xFF16211A, 0xFF12161E, 0xFF000000
    };
    /** 黑夜正文 */
    public static final int[] NIGHT_TEXT = {
            0xFFA9ABB0, 0xFFA69C8B, 0xFF93A596, 0xFF8E9BAE, 0xFF7E7E7E
    };

    public static final int COUNT = 5;

    private ReadThemes() {
    }

    public static int bg(int index, boolean night) {
        int i = clamp(index);
        return night ? NIGHT_BG[i] : DAY_BG[i];
    }

    public static int text(int index, boolean night) {
        int i = clamp(index);
        return night ? NIGHT_TEXT[i] : DAY_TEXT[i];
    }

    /** 页脚 / 辅助文字颜色（带透明度） */
    public static int hint(int index, boolean night) {
        int c = text(index, night);
        int alpha = night ? 0xFF : 0xB4;
        return (alpha << 24) | (c & 0x00FFFFFF);
    }

    private static int clamp(int i) {
        if (i < 0) {
            return 0;
        }
        if (i >= COUNT) {
            return COUNT - 1;
        }
        return i;
    }
}
